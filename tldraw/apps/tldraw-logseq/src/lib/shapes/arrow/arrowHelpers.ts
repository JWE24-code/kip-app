import type { Decoration } from '@tldraw/core'
import { intersectCircleLineSegment } from '@tldraw/intersect'
import Vec from '@tldraw/vec'

export function getArrowArcPath(start: number[], end: number[], circle: number[], bend: number) {
  return [
    'M',
    start[0],
    start[1],
    'A',
    circle[2],
    circle[2],
    0,
    0,
    bend < 0 ? 0 : 1,
    end[0],
    end[1],
  ].join(' ')
}

/**
 * Control points for a smooth "mindmap" S-curve between two connected shapes.
 * The curve leaves `start` and enters `end` along the dominant axis, so a
 * left-to-right connector flows horizontally and a stacked one flows
 * vertically — the shape people expect from a canvas mindmap. When the two
 * ends share a row or column the control points collapse onto the line and
 * it renders straight, which is what you want for an aligned parent/child.
 * `reach` (half the dominant-axis span, min 40) keeps both control points
 * inside the endpoints' bounding box for any link longer than ~110px, so the
 * curve doesn't get clipped. Callers decide *when* to curve (see LineShape:
 * only when both ends are bound); a free-standing line stays straight.
 */
export function getCurveControlPoints(start: number[], end: number[]) {
  const dx = end[0] - start[0]
  const dy = end[1] - start[1]
  const horizontal = Math.abs(dx) >= Math.abs(dy)
  const reach = Math.max(40, (horizontal ? Math.abs(dx) : Math.abs(dy)) * 0.5)
  const sx = Math.sign(dx) || 1
  const sy = Math.sign(dy) || 1
  const c1 = horizontal ? [start[0] + sx * reach, start[1]] : [start[0], start[1] + sy * reach]
  const c2 = horizontal ? [end[0] - sx * reach, end[1]] : [end[0], end[1] - sy * reach]
  return [c1, c2]
}

export function getCurvedArrowPath(start: number[], end: number[]) {
  const [c1, c2] = getCurveControlPoints(start, end)
  return `M ${Vec.toFixed(start)} C ${Vec.toFixed(c1)} ${Vec.toFixed(c2)} ${Vec.toFixed(end)}`
}

export function getStraightArrowHeadPoints(A: number[], B: number[], r: number) {
  const ints = intersectCircleLineSegment(A, r, A, B).points
  if (!ints) {
    console.warn('Could not find an intersection for the arrow head.')
    return { left: A, right: A }
  }
  const int = ints[0]
  const left = int ? Vec.rotWith(int, A, Math.PI / 6) : A
  const right = int ? Vec.rotWith(int, A, -Math.PI / 6) : A
  return { left, right }
}

export function getStraightArrowHeadPath(A: number[], B: number[], r: number) {
  const { left, right } = getStraightArrowHeadPoints(A, B, r)
  return `M ${left} L ${A} ${right}`
}

export function getArrowPath(
  style: {
    strokeWidth: number
  },
  start: number[],
  end: number[],
  decorationStart: Decoration | undefined,
  decorationEnd: Decoration | undefined,
  curved = false
) {
  const strokeWidth = style.strokeWidth
  const arrowDist = Vec.dist(start, end)
  const arrowHeadLength = Math.min(arrowDist / 3, strokeWidth * 16)
  const path: (string | number)[] = []
  // When curved, the arrowheads follow the curve's end tangents (toward the
  // near control point) instead of the straight chord.
  const ctrl = curved ? getCurveControlPoints(start, end) : null
  path.push(curved ? getCurvedArrowPath(start, end) : `M ${start} L ${end}`)
  if (decorationStart) {
    path.push(getStraightArrowHeadPath(start, ctrl ? ctrl[0] : end, arrowHeadLength))
  }
  if (decorationEnd) {
    path.push(getStraightArrowHeadPath(end, ctrl ? ctrl[1] : start, arrowHeadLength))
  }
  return path.join(' ')
}
