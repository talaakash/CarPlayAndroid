// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "iptv_native",
    platforms: [
        .iOS("15.0")
    ],
    products: [
        .library(name: "iptv-native", targets: ["iptv_native"])
    ],
    dependencies: [
        .package(name: "FlutterFramework", path: "../FlutterFramework")
    ],
    targets: [
        .target(
            name: "iptv_native",
            dependencies: [
                .product(name: "FlutterFramework", package: "FlutterFramework")
            ]
        )
    ]
)
