# Remote Keyboard

Android Bluetooth HID remote keyboard.

## Current implementation

- Android 9+ baseline (API 28)
- Bluetooth HID Device profile
- Laptop pairing and connection workflow
- Phone discoverability flow
- Live typing with changed-tail reconstruction for native keyboard/autocorrect input
- Buffered compose-and-send mode
- Enter, Backspace, Tab, Escape and arrow controls
- US-layout ASCII HID mapping
- Serialized HID press/release delivery with connection-epoch protection
- Connection status notification support

## Limitations

This release remains dependent on the phone manufacturer's Bluetooth HID Device support. Text transport is intentionally limited to characters representable by the bundled US-layout HID mapping; Unicode, emoji, clipboard transfer, and mouse control require a companion protocol or future transport layer.
