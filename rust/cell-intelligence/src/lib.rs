use jni::objects::{JDoubleArray, JObject};
use jni::sys::{jdoubleArray, jint};
use jni::JNIEnv;

const HUBER_THRESHOLD: f64 = 1.5;

fn read_double_array<'local>(
    env: &mut JNIEnv<'local>,
    array: &JDoubleArray<'local>,
) -> Option<Vec<f64>> {
    let length = env.get_array_length(array).ok()? as usize;
    let mut values = vec![0.0; length];
    env.get_double_array_region(array, 0, &mut values).ok()?;
    Some(values)
}

fn write_double_array<'local>(env: &mut JNIEnv<'local>, values: &[f64]) -> jdoubleArray {
    let array = match env.new_double_array(values.len() as jint) {
        Ok(array) => array,
        Err(_) => return std::ptr::null_mut(),
    };
    if env.set_double_array_region(&array, 0, values).is_err() {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

fn linear_initial_guess(
    x: &[f64],
    y: &[f64],
    ranges: &[f64],
    sigmas: &[f64],
) -> Option<(f64, f64)> {
    let anchor_x = *x.first()?;
    let anchor_y = *y.first()?;
    let anchor_range = *ranges.first()?;
    let anchor_sigma = *sigmas.first()?;
    let anchor_squared = anchor_x * anchor_x + anchor_y * anchor_y
        - anchor_range * anchor_range;

    let mut h11 = 0.0;
    let mut h12 = 0.0;
    let mut h22 = 0.0;
    let mut g1 = 0.0;
    let mut g2 = 0.0;

    for index in 1..x.len() {
        let a = 2.0 * (x[index] - anchor_x);
        let b = 2.0 * (y[index] - anchor_y);
        let rhs = x[index] * x[index] + y[index] * y[index]
            - ranges[index] * ranges[index] - anchor_squared;
        let variance = sigmas[index] * sigmas[index] + anchor_sigma * anchor_sigma;
        let weight = 1.0 / variance.max(1.0);
        h11 += weight * a * a;
        h12 += weight * a * b;
        h22 += weight * b * b;
        g1 += weight * a * rhs;
        g2 += weight * b * rhs;
    }

    let determinant = h11 * h22 - h12 * h12;
    if !determinant.is_finite() || determinant <= 1e-12 {
        return None;
    }
    let result_x = (g1 * h22 - g2 * h12) / determinant;
    let result_y = (g2 * h11 - g1 * h12) / determinant;
    if result_x.is_finite()
        && result_y.is_finite()
        && result_x.hypot(result_y) <= 150_000.0
    {
        Some((result_x, result_y))
    } else {
        None
    }
}

fn percentile(sorted: &[f64], fraction: f64) -> f64 {
    if sorted.is_empty() {
        return f64::INFINITY;
    }
    let index = (((sorted.len() - 1) as f64) * fraction) as usize;
    sorted[index.min(sorted.len() - 1)]
}

fn fit_timing_advance(
    x: &[f64],
    y: &[f64],
    ranges: &[f64],
    sigmas: &[f64],
    maximum_iterations: usize,
    bearing_bin_degrees: usize,
) -> Option<[f64; 8]> {
    let count = x.len();
    if count < 6
        || y.len() != count
        || ranges.len() != count
        || sigmas.len() != count
        || maximum_iterations == 0
        || !(15..=90).contains(&bearing_bin_degrees)
        || 360 % bearing_bin_degrees != 0
        || x.iter().chain(y).chain(ranges).chain(sigmas).any(|v| !v.is_finite())
        || ranges.iter().any(|v| *v < 0.0)
        || sigmas.iter().any(|v| *v <= 0.0)
    {
        return None;
    }

    let (mut estimate_x, mut estimate_y) = linear_initial_guess(x, y, ranges, sigmas)?;
    let mut h11 = 0.0;
    let mut h12 = 0.0;
    let mut h22 = 0.0;

    for _ in 0..maximum_iterations {
        h11 = 0.0;
        h12 = 0.0;
        h22 = 0.0;
        let mut g1 = 0.0;
        let mut g2 = 0.0;

        for index in 0..count {
            let dx = estimate_x - x[index];
            let dy = estimate_y - y[index];
            let distance = dx.hypot(dy).max(1.0);
            let jx = dx / distance;
            let jy = dy / distance;
            let residual = distance - ranges[index];
            let normalized = residual.abs() / sigmas[index];
            let robust_weight = if normalized <= HUBER_THRESHOLD {
                1.0
            } else {
                HUBER_THRESHOLD / normalized
            };
            let weight = robust_weight / (sigmas[index] * sigmas[index]);
            h11 += weight * jx * jx;
            h12 += weight * jx * jy;
            h22 += weight * jy * jy;
            g1 += weight * jx * residual;
            g2 += weight * jy * residual;
        }

        let determinant = h11 * h22 - h12 * h12;
        if !determinant.is_finite() || determinant <= 1e-14 {
            return None;
        }
        let step_x = (g1 * h22 - g2 * h12) / determinant;
        let step_y = (g2 * h11 - g1 * h12) / determinant;
        if !step_x.is_finite() || !step_y.is_finite() {
            return None;
        }
        estimate_x -= step_x;
        estimate_y -= step_y;
        if !estimate_x.is_finite()
            || !estimate_y.is_finite()
            || estimate_x.hypot(estimate_y) > 150_000.0
        {
            return None;
        }
        if step_x.hypot(step_y) < 0.1 {
            break;
        }
    }

    let mut residuals: Vec<f64> = (0..count)
        .map(|index| {
            ((estimate_x - x[index]).hypot(estimate_y - y[index]) - ranges[index]).abs()
        })
        .collect();
    residuals.sort_by(f64::total_cmp);
    let median = percentile(&residuals, 0.50);
    let p90 = percentile(&residuals, 0.90);

    let mut occupied = vec![false; 360 / bearing_bin_degrees];
    for index in 0..count {
        let east = x[index] - estimate_x;
        let north = y[index] - estimate_y;
        if east.hypot(north) >= 1.0 {
            let bearing = east.atan2(north).to_degrees().rem_euclid(360.0);
            let bin = (bearing / bearing_bin_degrees as f64) as usize;
            occupied[bin.min(occupied.len() - 1)] = true;
        }
    }
    let occupied_bins = occupied.iter().filter(|value| **value).count();

    Some([
        estimate_x,
        estimate_y,
        h11,
        h12,
        h22,
        median,
        p90,
        occupied_bins as f64,
    ])
}

fn normalize(angle: f64) -> f64 {
    angle.rem_euclid(360.0)
}

fn angular_difference(a: f64, b: f64) -> f64 {
    let difference = (normalize(a) - normalize(b)).abs().rem_euclid(360.0);
    difference.min(360.0 - difference)
}

fn median(values: &[f64]) -> f64 {
    if values.is_empty() {
        return f64::NAN;
    }
    let mut sorted = values.to_vec();
    sorted.sort_by(f64::total_cmp);
    let middle = sorted.len() / 2;
    if sorted.len() % 2 == 0 {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    } else {
        sorted[middle]
    }
}

#[derive(Clone, Copy)]
struct SectorModel {
    azimuth: f64,
    width: f64,
    contrast: f64,
    occupied_bins: usize,
}

fn contiguous_bins(peak: usize, values: &[Option<f64>], threshold: f64) -> Vec<usize> {
    let mut included = vec![false; values.len()];
    included[peak] = true;
    let mut result = vec![peak];

    for direction in [-1isize, 1isize] {
        let mut index = peak;
        for _ in 0..(values.len() / 2) {
            let next = (index as isize + direction + values.len() as isize)
                .rem_euclid(values.len() as isize) as usize;
            if included[next] {
                break;
            }
            let Some(value) = values[next] else {
                break;
            };
            if value < threshold {
                break;
            }
            included[next] = true;
            result.push(next);
            index = next;
        }
    }
    result
}

fn evaluate_sector(
    bearings: &[f64],
    distances: &[f64],
    rssi: &[f64],
    angular_bin_degrees: usize,
    minimum_occupied_bins: usize,
    minimum_signal_contrast_db: f64,
    maximum_model_spread_degrees: f64,
) -> Option<[f64; 6]> {
    let count = bearings.len();
    if count < 6
        || distances.len() != count
        || rssi.len() != count
        || !(5..=30).contains(&angular_bin_degrees)
        || 360 % angular_bin_degrees != 0
        || minimum_occupied_bins < 3
        || !minimum_signal_contrast_db.is_finite()
        || minimum_signal_contrast_db < 0.0
        || !maximum_model_spread_degrees.is_finite()
        || maximum_model_spread_degrees <= 0.0
        || bearings.iter().chain(distances).chain(rssi).any(|v| !v.is_finite())
        || distances.iter().any(|v| *v <= 0.0)
    {
        return None;
    }

    let bin_count = 360 / angular_bin_degrees;
    let mut models = Vec::new();
    for exponent in [2.0, 2.5, 3.0, 3.5, 4.0] {
        let mut bins: Vec<Vec<f64>> = (0..bin_count).map(|_| Vec::new()).collect();
        for index in 0..count {
            let bin = (normalize(bearings[index]) / angular_bin_degrees as f64) as usize;
            let corrected =
                rssi[index] + 10.0 * exponent * (distances[index] / 100.0).log10();
            bins[bin.min(bin_count - 1)].push(corrected);
        }

        let values: Vec<Option<f64>> = bins
            .iter()
            .map(|samples| {
                if samples.is_empty() {
                    None
                } else {
                    Some(samples.iter().sum::<f64>() / samples.len() as f64)
                }
            })
            .collect();
        let occupied = values.iter().filter(|value| value.is_some()).count();
        if occupied < minimum_occupied_bins {
            continue;
        }

        let non_empty: Vec<f64> = values.iter().flatten().copied().collect();
        let baseline = median(&non_empty);
        let Some(peak) = non_empty.iter().copied().reduce(f64::max) else {
            continue;
        };
        let contrast = peak - baseline;
        if contrast < minimum_signal_contrast_db {
            continue;
        }

        let smoothed: Vec<Option<f64>> = (0..bin_count)
            .map(|index| {
                let neighbours = [
                    values[(index + bin_count - 1) % bin_count],
                    values[index],
                    values[(index + 1) % bin_count],
                ];
                let present: Vec<f64> = neighbours.iter().flatten().copied().collect();
                if present.is_empty() {
                    None
                } else {
                    Some(present.iter().sum::<f64>() / present.len() as f64)
                }
            })
            .collect();

        let mut peak_bin = 0usize;
        for index in 1..bin_count {
            if smoothed[index].unwrap_or(f64::NEG_INFINITY)
                > smoothed[peak_bin].unwrap_or(f64::NEG_INFINITY)
            {
                peak_bin = index;
            }
        }
        let width_bins = contiguous_bins(peak_bin, &smoothed, peak - 4.0);
        let mut vector_x = 0.0;
        let mut vector_y = 0.0;
        for index in &width_bins {
            let angle = (*index as f64 + 0.5) * angular_bin_degrees as f64;
            let value = smoothed[*index].unwrap_or(baseline);
            let weight = 10.0f64.powf(((value - baseline).clamp(0.0, 20.0)) / 10.0);
            vector_x += weight * angle.to_radians().cos();
            vector_y += weight * angle.to_radians().sin();
        }
        let azimuth = normalize(vector_y.atan2(vector_x).to_degrees());
        models.push(SectorModel {
            azimuth,
            width: (width_bins.len() * angular_bin_degrees).min(180) as f64,
            contrast,
            occupied_bins: occupied,
        });
    }

    if models.len() < 3 {
        return None;
    }
    let mut best = models[0];
    for model in models.iter().skip(1) {
        if model.contrast > best.contrast {
            best = *model;
        }
    }
    let spread = models
        .iter()
        .map(|model| angular_difference(best.azimuth, model.azimuth))
        .fold(0.0, f64::max);
    if spread > maximum_model_spread_degrees {
        return None;
    }

    let widths: Vec<f64> = models.iter().map(|model| model.width).collect();
    let width = median(&widths);
    let bin_score = (best.occupied_bins as f64 / 18.0).clamp(0.0, 1.0);
    let sample_score = (count as f64 / 60.0).clamp(0.0, 1.0);
    let contrast_score =
        ((best.contrast - minimum_signal_contrast_db) / 12.0).clamp(0.0, 1.0);
    let stability = 1.0 - spread / maximum_model_spread_degrees;
    let confidence = (0.30 * sample_score
        + 0.25 * bin_score
        + 0.25 * contrast_score
        + 0.20 * stability)
        .clamp(0.0, 1.0);

    Some([
        normalize(best.azimuth),
        width,
        confidence,
        count as f64,
        best.occupied_bins as f64,
        best.contrast,
    ])
}

#[no_mangle]
pub extern "system" fn Java_org_cleargps_cell_CellRustBridge_nativeFitTimingAdvance<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    x: JDoubleArray<'local>,
    y: JDoubleArray<'local>,
    ranges: JDoubleArray<'local>,
    sigmas: JDoubleArray<'local>,
    maximum_iterations: jint,
    bearing_bin_degrees: jint,
) -> jdoubleArray {
    let result = match (
        read_double_array(&mut env, &x),
        read_double_array(&mut env, &y),
        read_double_array(&mut env, &ranges),
        read_double_array(&mut env, &sigmas),
    ) {
        (Some(x), Some(y), Some(ranges), Some(sigmas)) => fit_timing_advance(
            &x,
            &y,
            &ranges,
            &sigmas,
            maximum_iterations.max(0) as usize,
            bearing_bin_degrees.max(0) as usize,
        )
        .map(|values| values.to_vec())
        .unwrap_or_default(),
        _ => Vec::new(),
    };
    write_double_array(&mut env, &result)
}

#[no_mangle]
pub extern "system" fn Java_org_cleargps_cell_CellRustBridge_nativeEvaluateSector<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    bearings: JDoubleArray<'local>,
    distances: JDoubleArray<'local>,
    rssi: JDoubleArray<'local>,
    angular_bin_degrees: jint,
    minimum_occupied_bins: jint,
    minimum_signal_contrast_db: f64,
    maximum_model_spread_degrees: f64,
) -> jdoubleArray {
    let result = match (
        read_double_array(&mut env, &bearings),
        read_double_array(&mut env, &distances),
        read_double_array(&mut env, &rssi),
    ) {
        (Some(bearings), Some(distances), Some(rssi)) => evaluate_sector(
            &bearings,
            &distances,
            &rssi,
            angular_bin_degrees.max(0) as usize,
            minimum_occupied_bins.max(0) as usize,
            minimum_signal_contrast_db,
            maximum_model_spread_degrees,
        )
        .map(|values| values.to_vec())
        .unwrap_or_default(),
        _ => Vec::new(),
    };
    write_double_array(&mut env, &result)
}

#[cfg(test)]
mod tests {
    use super::{evaluate_sector, fit_timing_advance};

    #[test]
    fn timing_advance_fit_recovers_center_from_surrounding_ranges() {
        let mut x = Vec::new();
        let mut y = Vec::new();
        let mut ranges = Vec::new();
        let mut sigmas = Vec::new();
        for index in 0..16 {
            let bearing = (index as f64 * 22.5).to_radians();
            let distance = 600.0 + (index % 4) as f64 * 180.0;
            x.push(distance * bearing.sin());
            y.push(distance * bearing.cos());
            ranges.push(distance);
            sigmas.push(15.0);
        }

        let result = fit_timing_advance(&x, &y, &ranges, &sigmas, 30, 45).unwrap();
        assert!(result[0].abs() < 0.01);
        assert!(result[1].abs() < 0.01);
        assert!(result[5] < 0.01);
        assert!(result[6] < 0.01);
        assert!(result[7] >= 4.0);
    }

    #[test]
    fn sector_models_recover_a_directional_signal_pattern() {
        let mut bearings = Vec::new();
        let mut distances = Vec::new();
        let mut rssi = Vec::new();
        for bearing in (0..360).step_by(10) {
            for distance in [300.0, 450.0, 600.0] {
                let difference = ((bearing as f64 - 80.0 + 540.0) % 360.0 - 180.0).abs();
                let gain = if difference <= 65.0 { 16.0 } else { 0.0 };
                bearings.push(bearing as f64);
                distances.push(distance);
                rssi.push(-45.0 - 30.0 * distance.log10() + gain);
            }
        }

        let result = evaluate_sector(&bearings, &distances, &rssi, 10, 6, 3.0, 30.0).unwrap();
        let difference = ((result[0] - 80.0 + 540.0) % 360.0 - 180.0).abs();
        assert!(difference < 20.0);
        assert!(result[2] >= 0.60);
    }

    #[test]
    fn malformed_inputs_are_rejected() {
        assert!(fit_timing_advance(&[1.0], &[1.0], &[1.0], &[1.0], 30, 45).is_none());
        assert!(evaluate_sector(&[0.0; 8], &[0.0; 8], &[-50.0; 8], 10, 6, 3.0, 30.0).is_none());
    }
}
