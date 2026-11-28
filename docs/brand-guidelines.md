# Kitna and MT Studio artwork

## Roles

**Kitna** is the app name and primary product identity. Its existing mark/launcher artwork remains the app identifier. **MT Studio** is the creator identity, displayed in About and the Account/Help context with the wording **Created by MT Studio**.

The supplied creator artwork is stored as Android drawable resources:

- `app/src/main/res/drawable-nodpi/mt_studio_banner.png`: the original wide MT Studio artwork, displayed with its supplied white background and clear space.
- `app/src/main/res/drawable-nodpi/mt_studio_monogram.png`: the original compact MT Studio monogram.

These are user-supplied assets, not newly generated replacements. About displays them using `ContentScale.Fit`, without tinting, cropping, stretching or redrawing the artwork. Preserve their aspect ratio. The wide banner's existing whitespace is part of its supplied composition; keep it visible against both app themes. Give the images meaningful accessibility descriptions rather than using their file names as labels.

## Manga Panels interface and revised layout

For version 0.7.1, the user requested replacing the brown/warm base palette. Use cool neutral light surfaces, navy/slate dark surfaces and accessible indigo/blue actions. Keep the Manga Panels typography, panel structure, original artwork and saved light/dark preference. Explicitly chosen group colors remain available. Keep labels, controls, amounts and navigation readable at system text sizes. The creator artwork retains its original colors/background; do not recolor it to match budget or expense states.

Budget risk keeps labeled severity states. Expense magnitude is conveyed through amount size/weight and subtle row emphasis, without a "Large expense" caption. Balance phrases and amounts explain who owes whom; color is a supporting cue.

## Creator and support destinations

- GitHub: [MT-Studio-dev](https://github.com/MT-Studio-dev)
- Support and privacy contact: [official.mtstudio.dev@gmail.com](mailto:official.mtstudio.dev@gmail.com)

The app opens the browser or email composer at the user's request. It does not send a message automatically. Diagnostic sharing must remain an explicit action and must not attach the ledger, receipt files, account tokens or other credentials by default.
