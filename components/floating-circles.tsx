/**
 * Ported from _legacy/assets/css/main.css:15694-15806
 *   10 translucent shapes float upward, rotating, morphing from square
 *   to circle as they rise. Matches the legacy `<ul class="circles">`.
 */
const items = [
  { left: "25%", size:  80, delay:  0, duration: 25 },
  { left: "10%", size:  20, delay:  2, duration: 12 },
  { left: "70%", size:  20, delay:  4, duration: 25 },
  { left: "40%", size:  60, delay:  0, duration: 18 },
  { left: "65%", size:  20, delay:  0, duration: 25 },
  { left: "75%", size: 110, delay:  3, duration: 25 },
  { left: "35%", size: 150, delay:  7, duration: 25 },
  { left: "50%", size:  25, delay: 15, duration: 45 },
  { left: "20%", size:  15, delay:  2, duration: 35 },
  { left: "85%", size: 150, delay:  0, duration: 11 },
];

export function FloatingCircles({ tint = "white" }: { tint?: "white" | "brand" }) {
  const bg = tint === "brand" ? "rgba(42, 140, 219, 0.45)" : "rgba(255, 255, 255, 0.5)";
  return (
    <ul className="pointer-events-none absolute inset-0 overflow-hidden" aria-hidden="true">
      {items.map((c, i) => (
        <li
          key={i}
          className="absolute block animate-anvi-rise"
          style={{
            left:              c.left,
            bottom:            -100,
            width:             c.size,
            height:            c.size,
            background:        bg,
            animationDelay:    `${c.delay}s`,
            animationDuration: `${c.duration}s`,
          }}
        />
      ))}
    </ul>
  );
}
