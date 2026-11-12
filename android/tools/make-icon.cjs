#!/usr/bin/env node
/**
 * 用 sleeping.webp 生成 App 图标前景（自适应图标用）。
 *
 * 为什么不放进 Gradle：素材是动画 WebP，要抽帧 + 最近邻缩放 + 抠掉 Zzz/气泡，
 * sharp 一条命令就能跑完；图标是一次性产物，没必要为它往 APK 里塞解码依赖。
 *
 * 用法（sharp 只装在临时目录，不进仓库）：
 *   $dir = Join-Path $env:TEMP 'deepy-tools'; mkdir $dir -Force; cd $dir
 *   npm init -y | Out-Null; npm i sharp --no-audit --no-fund
 *   node <repo>/android/tools/make-icon.cjs
 *
 * 可选参数：
 *   --source <path>   素材路径，默认 app/src/main/assets/packs/deepy/sleeping.webp
 *   --frame <n>       取第几帧，默认 32（闭眼 + 白眼罩那一帧）
 *   --ratio <0..1>    主体占画布比例，默认 0.56（再大就会被桌面遮罩切到尾巴）
 *   --keep-zzz        保留右上角的 Zzz（默认抠掉：48dp 下只是杂点）
 *   --keep-bubble     保留左侧气泡圈（默认抠掉）
 */
const fs = require('node:fs')
const path = require('node:path')

const ANDROID_ROOT = path.resolve(__dirname, '..')
const DEFAULT_SOURCE = path.join(ANDROID_ROOT, 'app/src/main/assets/packs/deepy/sleeping.webp')
const RES = path.join(ANDROID_ROOT, 'app/src/main/res')

/** mipmap 目录 → 108dp 画布对应的像素边长。 */
const DENSITIES = { mdpi: 108, hdpi: 162, xhdpi: 216, xxhdpi: 324, xxxhdpi: 432 }

/** 抠图区域（源素材 224×224 坐标系）：右上角 Zzz、左侧气泡圈。 */
const ZZZ = { x1: 136, x2: 172, y1: 0, y2: 100 }
const BUBBLE = { x1: 0, x2: 70, y1: 140, y2: 224 }

function loadSharp() {
  try {
    return require('sharp')
  } catch (error) {
    // 脚本放在仓库里、sharp 装在临时目录：再按 cwd 找一次。
    try {
      return require(path.join(process.cwd(), 'node_modules', 'sharp'))
    } catch {
      console.error('找不到 sharp。请在临时目录 `npm i sharp` 后再运行本脚本。')
      process.exit(1)
    }
  }
}

function parseArgs(argv) {
  const options = { source: DEFAULT_SOURCE, frame: 32, ratio: 0.56, keepZzz: false, keepBubble: false }
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index]
    const value = argv[index + 1]
    if (key === '--source') { options.source = value; index += 1 }
    else if (key === '--frame') { options.frame = Number(value) || 0; index += 1 }
    else if (key === '--ratio') { options.ratio = Math.min(Math.max(Number(value) || 0.56, 0.2), 1); index += 1 }
    else if (key === '--keep-zzz') options.keepZzz = true
    else if (key === '--keep-bubble') options.keepBubble = true
  }
  return options
}

/** 取一帧 → 抠掉装饰像素 → 裁掉透明边，得到「鲸鱼本体」。 */
async function extractBody(sharp, options) {
  const { data, info } = await sharp(options.source, { page: options.frame, pages: 1 })
    .ensureAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true })
  const { width, height } = info
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const inZzz = !options.keepZzz && x > ZZZ.x1 && x < ZZZ.x2 && y < ZZZ.y2
      const inBubble = !options.keepBubble && x < BUBBLE.x2 && y > BUBBLE.y1
      if (inZzz || inBubble) data[(y * width + x) * 4 + 3] = 0
    }
  }
  const cleaned = await sharp(data, { raw: { width, height, channels: 4 } }).png().toBuffer()
  const trimmed = await sharp(cleaned).trim({ threshold: 1 }).png().toBuffer({ resolveWithObject: true })
  return trimmed
}

async function main() {
  const sharp = loadSharp()
  const options = parseArgs(process.argv.slice(2))
  if (!fs.existsSync(options.source)) {
    console.error(`素材不存在：${options.source}`)
    process.exit(1)
  }
  const body = await extractBody(sharp, options)
  console.log(`鲸鱼本体：${body.info.width}×${body.info.height}，主体比例 ${options.ratio}`)

  for (const [density, canvas] of Object.entries(DENSITIES)) {
    const box = Math.round(canvas * options.ratio)
    const scale = Math.min(box / body.info.width, box / body.info.height)
    const width = Math.max(1, Math.round(body.info.width * scale))
    const height = Math.max(1, Math.round(body.info.height * scale))
    // 像素画必须最近邻，双线性一缩就糊成一片。
    const inner = await sharp(body.data).resize(width, height, { kernel: 'nearest' }).png().toBuffer()
    const target = path.join(RES, `mipmap-${density}`, 'ic_launcher_foreground.png')
    fs.mkdirSync(path.dirname(target), { recursive: true })
    await sharp({
      create: { width: canvas, height: canvas, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } },
    })
      .composite([{
        input: inner,
        left: Math.round((canvas - width) / 2),
        top: Math.round((canvas - height) / 2),
      }])
      .png({ compressionLevel: 9 })
      .toFile(target)
    console.log(`  ${density.padEnd(8)} ${canvas}px → ${target.replace(ANDROID_ROOT, 'android')}`)
  }
  console.log('完成。背景色在 res/mipmap-anydpi-v26/ic_launcher.xml 里引用 @color/deep_bg。')
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
