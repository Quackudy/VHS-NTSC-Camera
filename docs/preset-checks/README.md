# Preset import checks

These are diagnostic ntsc-rs version-1 JSON files, not finished shooting presets.
They use the same upstream legacy defaults except `snow_intensity` (0 versus 1).
Import each in Camera Settings with live preview enabled. The heavy-snow file
adds visible white noise; the file name should change in the selected-preset label.
Existing effects from the legacy defaults remain active in both files.
Invalid files such as PDFs must show a rejection dialog and retain the previous
preset. Saved originals stay unprocessed; compare live preview or a processed copy.
