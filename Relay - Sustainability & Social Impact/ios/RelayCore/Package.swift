// swift-tools-version:6.0
import PackageDescription

// RelayCore: wire protocol, gossip, intake (port of android/core-protocol).
// RelayKit: platform-free app logic (stores, gateway sync, BLE logic, i18n, screen logic; port of android/app non-UI code).
// Both build and test on Linux and macOS; iOS-only code (CoreBluetooth, SwiftUI, speech) lives in the Xcode app.
let package = Package(
    name: "RelayCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "RelayCore", targets: ["RelayCore"]),
        .library(name: "RelayKit", targets: ["RelayKit"]),
    ],
    targets: [
        .target(name: "RelayCore"),
        .target(name: "RelayKit", dependencies: ["RelayCore"]),
        .testTarget(name: "RelayCoreTests", dependencies: ["RelayCore"]),
        .testTarget(name: "RelayKitTests", dependencies: ["RelayKit"]),
    ],
    swiftLanguageModes: [.v6]
)
