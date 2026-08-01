# Design reference

Screens are 1188 x 2436 px, which is 412 x 892 dp at xxhdpi (@3x).

## What is here

| File | Screen |
|---|---|
| `skjermer/01-velkommen.png` | Welcome |
| `skjermer/02-kode.png` | Enter invite code |
| `skjermer/03-velg-mapper.png` | Choose what to back up |
| `skjermer/06-filer.png` | Browse backed-up files |

`grafikk/` holds the launcher icon (`ikon-app-1024.png`), the adaptive-icon
foreground layer, the monochrome themed-icon layer, the palette chart, and the
icon sheet on a 24 dp grid with a 1.8 px stroke.

The icon sheet is a raster sheet, so each icon is redrawn as a vector drawable
rather than sliced into bitmaps.

## What is deliberately missing

Four screens (Home, Backing up now, Family, Something is missing) are not in this
directory. The exported mockups embed a real person's first name in the greeting,
the family member list and the "call someone for help" row. They will be added
once re-exported with a placeholder name.

Their behaviour is fully specified in the roadmap regardless; the missing files
are reference images, not requirements.
