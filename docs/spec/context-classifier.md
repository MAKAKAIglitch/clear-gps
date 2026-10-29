# GNSS environment context

Status: conservative draft.

The classifier is only a hint about observation conditions. It returns OPEN_SKY_LIKELY, OBSTRUCTED_OR_MULTIPATH_POSSIBLE or UNKNOWN, with a low-confidence reason. Several healthy satellites with adequate CN0 support an open-sky hint. Weak GNSS alongside a dense radio scan supports only a possibility of obstruction/multipath. Weak GNSS alone remains UNKNOWN because it can be caused by a tunnel, indoor location, receiver fault, jamming or other factors.

Context has no spoofing score and must not clear independent attack evidence. Missing AGC, radio scan, frequency band or satellite metrics remain unavailable, never zero. Thresholds are initial heuristics and require labelled replay evaluation. Future features may use temporal changes, motion, satellite geometry and hardware capability, but need tests proving they do not suppress simple attacks.
