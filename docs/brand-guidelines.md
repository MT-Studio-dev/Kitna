# Kitna and MT Studio artwork

## Roles

**Kitna** is the app name and primary product identity. Its existing mark/launcher artwork remains the app identifier. **MT Studio** is the creator identity, displayed in About and the Account/Help context with the wording **Created by MT Studio**.

The supplied creator artwork is stored as Android drawable resources:

- `app/src/main/res/drawable-nodpi/mt_studio_banner.png`: the original wide MT Studio artwork, displayed with its supplied white background and clear space.
- `app/src/main/res/drawable-nodpi/mt_studio_monogram.png`: the original compact MT Studio monogram.

These are user-supplied assets, not newly generated replacements. About displays them using `ContentScale.Fit`, without tinting, cropping, stretching or redrawing the artwork. Preserve their aspect ratio. The wide banner's existing whitespace is part of its supplied composition; keep it visible against both app themes. Give the images meaningful accessibility descriptions rather than using their file names as labels.

## Manga Panels interface

The selected product style uses cream/ink light surfaces, an appropriate dark counterpart, strong panel edges, warm accents and clear typographic hierarchy. Keep labels, controls, amounts and navigation readable at system text sizes. The creator artwork retains its own original colors/background; do not recolor it to match budget or expense states.

Budget risk has a separate labeled green/amber/orange/red scale. Stronger expense tint and amount weight represent magnitude, not payment verification, debt direction or a creator badge. Do not use color alone to convey those meanings.

## Creator and support destinations

- GitHub: [MT-Studio-dev](https://github.com/MT-Studio-dev)
- Support and privacy contact: [official.mtstudio.dev@gmail.com](mailto:official.mtstudio.dev@gmail.com)

The app opens the browser or email composer at the user's request. It does not send a message automatically. Diagnostic sharing must remain an explicit action and must not attach the ledger, receipt files, account tokens or other credentials by default.
