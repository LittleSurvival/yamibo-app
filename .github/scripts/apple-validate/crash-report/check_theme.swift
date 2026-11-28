import AppKit

let url = URL(fileURLWithPath: CommandLine.arguments[1])
let image = try NSBitmapImageRep(data: Data(contentsOf: url))!
let backdrop = image.colorAt(x: image.pixelsWide / 30, y: image.pixelsHigh / 4)!.usingColorSpace(.sRGB)!
let luminance = 0.2126 * backdrop.redComponent + 0.7152 * backdrop.greenComponent + 0.0722 * backdrop.blueComponent
let dark = CommandLine.arguments[2] == "CLASSIC_BLACK"
print("Recovery backdrop RGB \(backdrop.redComponent), \(backdrop.greenComponent), \(backdrop.blueComponent)")
precondition(dark ? luminance < 0.3 : luminance > 0.45, "Unexpected recovery palette")
