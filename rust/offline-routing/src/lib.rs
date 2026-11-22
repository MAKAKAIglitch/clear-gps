//! Clear Maps offline road graph and A* routing core.
//!
//! The on-device graph is a compact, versioned CSR-like binary blob. Routing allocates
//! only per-node score/parent arrays and a bounded binary heap; OSM parsing happens
//! during import/update, never during route calculation.
use std::cmp::Ordering;
use std::collections::BinaryHeap;

const MAGIC: &[u8; 8] = b"CLROUTE1";
const VERSION: u32 = 1;
const HEADER_BYTES: usize = 8 + 4 + 4 + 4;
const NODE_BYTES: usize = 16; // latitude, longitude (f64)
const EDGE_BYTES: usize = 12; // target (u32), distance_m (f32), speed_mps (f32)

#[derive(Clone, Copy, Debug)]
struct Node { lat: f64, lon: f64 }
#[derive(Clone, Copy, Debug)]
struct Edge { target: usize, distance: f64, speed: f64 }
#[derive(Debug)]
struct Graph { nodes: Vec<Node>, offsets: Vec<usize>, edges: Vec<Edge> }

#[derive(Clone, Copy)]
struct QueueItem { node: usize, cost: f64, estimate: f64 }
impl Eq for QueueItem {}
impl PartialEq for QueueItem { fn eq(&self, other: &Self) -> bool { self.estimate == other.estimate } }
impl Ord for QueueItem {
    fn cmp(&self, other: &Self) -> Ordering {
        other.estimate.total_cmp(&self.estimate).then_with(|| other.cost.total_cmp(&self.cost))
    }
}
impl PartialOrd for QueueItem { fn partial_cmp(&self, other: &Self) -> Option<Ordering> { Some(self.cmp(other)) } }

fn u32_at(data: &[u8], at: usize) -> Option<u32> {
    Some(u32::from_le_bytes(data.get(at..at + 4)?.try_into().ok()?))
}
fn f64_at(data: &[u8], at: usize) -> Option<f64> {
    Some(f64::from_le_bytes(data.get(at..at + 8)?.try_into().ok()?))
}
fn f32_at(data: &[u8], at: usize) -> Option<f32> {
    Some(f32::from_le_bytes(data.get(at..at + 4)?.try_into().ok()?))
}
fn graph(data: &[u8]) -> Option<Graph> {
    if data.len() < HEADER_BYTES || data.get(..8)? != MAGIC || u32_at(data, 8)? != VERSION { return None; }
    let node_count = u32_at(data, 12)? as usize;
    let edge_count = u32_at(data, 16)? as usize;
    if node_count < 2 || node_count > 20_000_000 || edge_count > 100_000_000 { return None; }
    let expected = HEADER_BYTES.checked_add(node_count.checked_mul(NODE_BYTES)?)?
        .checked_add((node_count + 1).checked_mul(4)?)?
        .checked_add(edge_count.checked_mul(EDGE_BYTES)?)?;
    if expected != data.len() { return None; }

    let mut cursor = HEADER_BYTES;
    let mut nodes = Vec::with_capacity(node_count);
    for _ in 0..node_count {
        let lat = f64_at(data, cursor)?;
        let lon = f64_at(data, cursor + 8)?;
        cursor += NODE_BYTES;
        if !lat.is_finite() || !lon.is_finite() || !(-90.0..=90.0).contains(&lat) || !(-180.0..=180.0).contains(&lon) { return None; }
        nodes.push(Node { lat, lon });
    }
    let mut offsets = Vec::with_capacity(node_count + 1);
    for _ in 0..=node_count { offsets.push(u32_at(data, cursor)? as usize); cursor += 4; }
    if offsets.first() != Some(&0) || offsets.last() != Some(&edge_count) ||
        offsets.windows(2).any(|w| w[0] > w[1] || w[1] > edge_count) { return None; }

    let mut edges = Vec::with_capacity(edge_count);
    for _ in 0..edge_count {
        let target = u32_at(data, cursor)? as usize;
        let distance = f32_at(data, cursor + 4)? as f64;
        let speed = f32_at(data, cursor + 8)? as f64;
        cursor += EDGE_BYTES;
        if target >= node_count || !distance.is_finite() || distance <= 0.0 || !speed.is_finite() || speed <= 0.0 { return None; }
        edges.push(Edge { target, distance, speed });
    }
    Some(Graph { nodes, offsets, edges })
}

fn haversine_m(a: Node, b: Node) -> f64 {
    let p1 = a.lat.to_radians();
    let p2 = b.lat.to_radians();
    let dp = (b.lat - a.lat).to_radians();
    let dl = (b.lon - a.lon).to_radians();
    let h = (dp / 2.0).sin().powi(2) + p1.cos() * p2.cos() * (dl / 2.0).sin().powi(2);
    6_371_000.0 * 2.0 * h.clamp(0.0, 1.0).sqrt().asin()
}
fn nearest(nodes: &[Node], lat: f64, lon: f64) -> Option<usize> {
    let point = Node { lat, lon };
    nodes.iter().enumerate().min_by(|(_, a), (_, b)|
        haversine_m(**a, point).total_cmp(&haversine_m(**b, point))).map(|(i, _)| i)
}

/// Returns [lat, lon, ...] for the fastest route. Input graph is validated before search.
pub fn find_route_bytes(data: &[u8], from_lat: f64, from_lon: f64, to_lat: f64, to_lon: f64) -> Option<Vec<f64>> {
    if [from_lat, from_lon, to_lat, to_lon].iter().any(|v| !v.is_finite()) ||
        !(-90.0..=90.0).contains(&from_lat) || !(-90.0..=90.0).contains(&to_lat) ||
        !(-180.0..=180.0).contains(&from_lon) || !(-180.0..=180.0).contains(&to_lon) { return None; }
    let graph = graph(data)?;
    let start = nearest(&graph.nodes, from_lat, from_lon)?;
    let goal = nearest(&graph.nodes, to_lat, to_lon)?;
    let mut best = vec![f64::INFINITY; graph.nodes.len()];
    let mut parent = vec![usize::MAX; graph.nodes.len()];
    let mut queue = BinaryHeap::new();
    best[start] = 0.0;
    queue.push(QueueItem { node: start, cost: 0.0, estimate: haversine_m(graph.nodes[start], graph.nodes[goal]) / 55.0 });
    while let Some(item) = queue.pop() {
        if item.cost > best[item.node] { continue; }
        if item.node == goal { break; }
        for edge in &graph.edges[graph.offsets[item.node]..graph.offsets[item.node + 1]] {
            // Time-based edge weight; heuristic uses a conservative 55 m/s upper bound.
            let next_cost = item.cost + edge.distance / edge.speed;
            if next_cost < best[edge.target] {
                best[edge.target] = next_cost;
                parent[edge.target] = item.node;
                let estimate = next_cost + haversine_m(graph.nodes[edge.target], graph.nodes[goal]) / 55.0;
                queue.push(QueueItem { node: edge.target, cost: next_cost, estimate });
            }
        }
    }
    if !best[goal].is_finite() { return None; }
    let mut path = vec![goal];
    let mut current = goal;
    while current != start {
        current = *parent.get(current)?;
        if current == usize::MAX || path.len() > graph.nodes.len() { return None; }
        path.push(current);
    }
    path.reverse();
    let mut coordinates = Vec::with_capacity(path.len() * 2);
    for index in path { coordinates.push(graph.nodes[index].lat); coordinates.push(graph.nodes[index].lon); }
    Some(coordinates)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixture() -> Vec<u8> {
        let nodes = [(55.0, 37.0), (55.0, 37.01), (55.01, 37.01), (55.01, 37.0)];
        let edges = [(1u32, 640.0f32, 20.0f32), (2, 1112.0, 20.0), (3, 640.0, 20.0), (0, 1112.0, 20.0)];
        let offsets = [0u32, 1, 2, 3, 4];
        let mut bytes = Vec::new();
        bytes.extend_from_slice(MAGIC); bytes.extend_from_slice(&VERSION.to_le_bytes());
        bytes.extend_from_slice(&(nodes.len() as u32).to_le_bytes());
        bytes.extend_from_slice(&(edges.len() as u32).to_le_bytes());
        for (lat, lon) in nodes { bytes.extend_from_slice(&lat.to_le_bytes()); bytes.extend_from_slice(&lon.to_le_bytes()); }
        for offset in offsets { bytes.extend_from_slice(&offset.to_le_bytes()); }
        for (target, distance, speed) in edges {
            bytes.extend_from_slice(&target.to_le_bytes()); bytes.extend_from_slice(&distance.to_le_bytes()); bytes.extend_from_slice(&speed.to_le_bytes());
        }
        bytes
    }
    #[test] fn finds_directed_path() {
        let route = find_route_bytes(&fixture(), 55.0, 37.0, 55.01, 37.01).unwrap();
        assert_eq!(route.len(), 6);
        assert_eq!(&route[..2], &[55.0, 37.0]);
        assert_eq!(&route[4..], &[55.01, 37.01]);
    }
    #[test] fn rejects_corrupt_graph_and_invalid_coordinates() {
        assert!(find_route_bytes(b"bad", 55.0, 37.0, 55.1, 37.1).is_none());
        assert!(find_route_bytes(&fixture(), 999.0, 37.0, 55.1, 37.1).is_none());
    }
}
