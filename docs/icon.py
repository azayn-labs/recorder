# Generates docs/icon.svg, the source of the launcher icon (108dp adaptive-icon grid).
# Usage: python3 docs/icon.py solid docs/icon.svg   (the shipped icon; "facet" = two-tone dot)
import sys
dot = sys.argv[1]  # "facet": two flat tones split diagonally, like the mic; "solid": flat navy
out = sys.argv[2]
dot_svg = (
    '<g clip-path="url(#dotClip)"><rect x="74" y="25" width="13" height="13" fill="#0A2A8C"/>'
    '<polygon points="74,25 87,25 74,38" fill="#1E7BFF"/></g>'
    if dot == "facet" else
    '<circle cx="80.5" cy="31.5" r="5.2" fill="#0A2A8C"/>'
)
svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="432" height="432">
  <defs>
    <linearGradient id="ring" x1="24" y1="24" x2="84" y2="84" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#22D3FF"/>
      <stop offset="0.42" stop-color="#1E7BFF"/>
      <stop offset="0.78" stop-color="#1446D6"/>
      <stop offset="1" stop-color="#0A2A8C"/>
    </linearGradient>
    <linearGradient id="micL" x1="45" y1="36" x2="54" y2="58" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#4FD6FF"/><stop offset="1" stop-color="#1E86FF"/>
    </linearGradient>
    <linearGradient id="micR" x1="54" y1="36" x2="63" y2="58" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#1A5FEA"/><stop offset="1" stop-color="#0A2A8C"/>
    </linearGradient>
    <linearGradient id="stand" x1="42" y1="50" x2="66" y2="72" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#1E7BFF"/><stop offset="1" stop-color="#0A2A8C"/>
    </linearGradient>
    <clipPath id="dotClip"><circle cx="80.5" cy="31.5" r="5.2"/></clipPath>
    <!-- "C" ring: outer 23..85 r18, inner 31..77 r11; both ends taper to sharp shard tips -->
    <path id="ringShape" d="M81 20 L41 23 A18 18 0 0 0 23 41 V67 A18 18 0 0 0 41 85 H67 A18 18 0 0 0 85 67 L87 36 L77 52 V66 A11 11 0 0 1 66 77 H42 A11 11 0 0 1 31 66 V42 A11 11 0 0 1 42 31 L62 31 Z"/>
    <clipPath id="ringClip"><use href="#ringShape"/></clipPath>
    <clipPath id="micClip"><rect x="45" y="35" width="18" height="24" rx="9"/></clipPath>
  </defs>

  <use href="#ringShape" style="fill:url(#ring)"/>
  <g clip-path="url(#ringClip)">
    <!-- crisp facet planes: highlight top-left, mid plane, shadow bottom-right -->
    <polygon points="15,15 64,15 15,64" fill="#FFFFFF" opacity="0.22"/>
    <polygon points="15,92 92,15 92,33 33,92" fill="#0A2A8C" opacity="0.12"/>
    <polygon points="92,46 92,92 46,92" fill="#061C6B" opacity="0.32"/>
    <path d="M77 52 V66 A11 11 0 0 1 66 77 H42 A11 11 0 0 1 31 66 V42 A11 11 0 0 1 42 31 L62 31" fill="none" stroke="#06205F" stroke-width="2.6" opacity="0.35"/>
  </g>

  <!-- microphone: two-tone faceted capsule with a pulse line -->
  <g clip-path="url(#micClip)">
    <rect x="45" y="35" width="18" height="24" fill="url(#micR)"/>
    <polygon points="44,34 59,34 49,60 44,60" fill="url(#micL)"/>
    <polyline points="44,48 48.5,48 50.8,42.5 54.2,53.5 56.6,45 58.2,48 64,48" fill="none" stroke="#FFFFFF" stroke-width="1.6" stroke-linejoin="miter" stroke-miterlimit="8"/>
  </g>
  <path d="M41.5 49.5 V51 A12.5 12.5 0 0 0 66.5 51 V49.5" fill="none" stroke="url(#stand)" stroke-width="3"/>
  <rect x="52.5" y="63.3" width="3" height="5" fill="#0F37B0"/>
  <polygon points="45,68 65,68 62.5,71.6 42.5,71.6" fill="#0A2A8C"/>

  <!-- record dot in the ring's gap -->
  {dot_svg}
</svg>
'''
open(out, 'w').write(svg)
