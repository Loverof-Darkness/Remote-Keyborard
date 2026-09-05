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

The app depends on the phone manufacturer's Bluetooth HID Device support. Text transport is limited to characters represented by the bundled US-layout HID mapping. Unicode, emoji, clipboard transfer, mouse control, and background operation are outside this MVP and require a companion protocol or future transport layer.
