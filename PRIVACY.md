# Kernel Panic Privacy Notice

Last updated: September 3, 2026

Kernel Panic works entirely on your Android device.

## Microphone

Kernel Panic asks for microphone permission only when you tap **Start Listening**. While the listening screen is active, microphone audio is analyzed in real time on the device to estimate acoustic pop events and microwave operation.

Release builds do not save raw microphone audio. Audio is not uploaded, transmitted, shared, or retained after real-time processing. Microphone capture stops and its resources are released when the session ends, when you press Stop, or when the app leaves the foreground.

Debug builds intended for developers include an Audio Lab that can process a WAV file explicitly selected by the developer or explicitly record a real test session into the app-specific external-files directory. This interface and recording code are not included in release builds.

## Stored data

Kernel Panic stores only derived session statistics locally, including the session time, duration, detected pop-event count, time to first event, peak estimated rate, final interval statistic, and completion reason. These values are not exact measurements of physical kernels.

There is no account or synchronization. You can delete individual sessions or all history from the History screen. App data is excluded from Android cloud backup and device transfer by the included backup rules.

## Network and third parties

Kernel Panic has no backend, advertising, analytics, or cloud-storage SDK. Its only sensitive runtime permission is microphone access; the normal Android vibration permission powers haptic alerts. The release manifest does not request Android's `INTERNET` permission. The app does not sell or share personal information.

## Safety

Kernel Panic is a convenience aid and can make mistakes. Stay near the microwave, follow the food manufacturer's directions, keep the phone outside the microwave, and use your own judgment.

This notice can be adapted for a hosted project privacy page before store distribution.
