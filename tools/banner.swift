// 生成 TV 横幅 (1280x720, 用 sips 缩到 640x360 放进 drawable-xhdpi):
//   swift tools/banner.swift app/src/main/res/drawable/malguem.png out.png
import AppKit

let w = 1280, h = 720
let icon = NSImage(contentsOfFile: CommandLine.arguments[1])!

// Sample the icon's flat field above the cloud; its corners are darker from the rounded-corner antialiasing.
let probe = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 64, pixelsHigh: 64, bitsPerSample: 8,
    samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 256, bitsPerPixel: 32)!
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: probe)
icon.draw(in: NSRect(x: 0, y: 0, width: 64, height: 64))
NSGraphicsContext.current = nil
let p = probe.bitmapData!, o = (4 * 64 + 32) * 4
let field = NSColor(deviceRed: CGFloat(p[o]) / 255, green: CGFloat(p[o + 1]) / 255, blue: CGFloat(p[o + 2]) / 255, alpha: 1)

let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: w, pixelsHigh: h, bitsPerSample: 8,
    samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: w * 4, bitsPerPixel: 32)!
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
field.setFill(); NSRect(x: 0, y: 0, width: w, height: h).fill()

// Oversize the icon so its rounded corners fall outside the banner: only the right edge stays visible,
// and a gradient in the same colour blends that one away. No pixel keying needed.
let box = NSRect(x: -60, y: -70, width: 860, height: 860)
icon.draw(in: box)
NSGradient(colors: [field.withAlphaComponent(0), field])!
    .draw(in: NSRect(x: 500, y: 0, width: 270, height: CGFloat(h)), angle: 0)

let ink = NSColor(deviceRed: 0.13, green: 0.47, blue: 0.69, alpha: 1)
let style = NSMutableParagraphStyle(); style.alignment = .left
NSAttributedString(string: "malguem", attributes: [
    .font: NSFont.systemFont(ofSize: 100, weight: .bold), .foregroundColor: ink, .paragraphStyle: style,
]).draw(in: NSRect(x: 770, y: 392, width: 500, height: 140))
NSAttributedString(string: "漫画 · 小说阅读器", attributes: [
    .font: NSFont.systemFont(ofSize: 48, weight: .medium),
    .foregroundColor: ink.withAlphaComponent(0.85), .paragraphStyle: style,
]).draw(in: NSRect(x: 776, y: 300, width: 500, height: 80))
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: CommandLine.arguments[2]))
