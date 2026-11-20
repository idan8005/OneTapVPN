// swift-tools-version: 5.10
import PackageDescription
let package=Package(
 name:"RaahatVPNCore",
 platforms:[.iOS(.v16),.macOS(.v13)],
 products:[.library(name:"RaahatVPNCore",targets:["RaahatVPNCore"])],
 targets:[.target(name:"RaahatVPNCore"),.testTarget(name:"RaahatVPNCoreTests",dependencies:["RaahatVPNCore"])]
)
