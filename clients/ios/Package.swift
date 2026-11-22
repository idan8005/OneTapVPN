// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "RaahatCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "RaahatCore", targets: ["RaahatCore"])
    ],
    targets: [
        .target(name: "RaahatCore"),
        .testTarget(name: "RaahatCoreTests", dependencies: ["RaahatCore"])
    ]
)
