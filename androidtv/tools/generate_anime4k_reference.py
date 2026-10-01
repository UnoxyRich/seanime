#!/usr/bin/env python3
"""Independent CPU reference for the unmodified Anime4K CNN x2 M, not GLES code.

Requires numpy. Rebuilds the tiny linear-BT.709 input/output fixture used by the
instrumentation test. Column-major GLSL matrices, clamped edges, positive/negative
ReLU channels, every signed RGBA16F intermediate, bilinear RGB residual, and
2x depth-to-space are implemented explicitly. GPU results allow rounding drift.
"""
from pathlib import Path
import gzip
import re
import numpy as np

ROOT = Path(__file__).resolve().parents[2]


def read_anime4k_source(name):
    """Read the original shader bytes, including build-time compressed sources."""
    plain = ROOT / 'androidtv/app/src/main/assets/anime4k' / name
    compressed = ROOT / 'androidtv/app/src/main/compressedAssets/anime4k' / (name + '.gz')
    assert plain.is_file() != compressed.is_file(), f'Expected exactly one source for {name}'
    data = plain.read_bytes() if plain.is_file() else gzip.decompress(compressed.read_bytes())
    return data.decode('utf-8')


SOURCE = ROOT / 'seanime-denshi/assets/shaders/Anime4K_Upscale_CNN_x2_M.glsl'
OUT = ROOT / 'androidtv/app/src/androidTest/assets/anime4k'
W = H = 8
pixels = np.empty((H, W, 4), dtype=np.uint8)
for y in range(H):
    for x in range(W):
        pixels[y, x] = [(x * 29 + y * 17 + ((x // 2 + y // 2) % 2) * 71) % 256,
                        (x * 13 + y * 41 + 53) % 256, (x * 47 + y * 11 + 19) % 256, 255]
linear = pixels.astype(np.float32) / 255
rgb = linear[:, :, :3]
encoded = np.where(rgb < .018, 4.5 * rgb, 1.099 * np.power(rgb, .45) - .099)
main = np.concatenate([encoded, np.ones((H, W, 1), np.float32)], axis=2).astype(np.float16).astype(np.float32)
textures = {'MAIN': main}
for index, chunk in enumerate(SOURCE.read_text().split('//!DESC ')[1:-1]):
    bindings = re.findall(r'^//!BIND (\w+)', chunk, re.M)
    save = re.search(r'^//!SAVE (\w+)', chunk, re.M).group(1)
    result = np.zeros((H, W, 4), np.float32)
    # The first seven passes are 3x3 convolution; pass eight mixes 14 feature maps.
    for match in re.finditer(r'mat4\(([^)]+)\)\s*\*\s*(go_|g_)(\d+)(?:\(([-\d.]+),\s*([-\d.]+)\))?', chunk):
        weights = np.array([float(n) for n in match[1].split(',')], np.float32).reshape(4, 4).T
        channel = int(match[3])
        if match[2] == 'go_':
            image = textures[bindings[0]]
            if index > 0:
                image = np.maximum(image if channel == 0 else -image, 0)
            dx, dy = int(float(match[4])), int(float(match[5]))
            image = image[np.clip(np.arange(H) + dy, 0, H - 1)[:, None], np.clip(np.arange(W) + dx, 0, W - 1)[None, :]]
        else:
            image = textures[bindings[channel // 2]]
            image = np.maximum(image if channel % 2 == 0 else -image, 0)
        result += image @ weights.T
    bias = re.findall(r'result\s*\+=\s*vec4\(([^)]+)\)', chunk)[-1]
    result += np.array([float(n) for n in bias.split(',')], np.float32)
    textures[save] = result.astype(np.float16).astype(np.float32)
last = textures['conv2d_last_tf']
output = np.empty((H * 2, W * 2, 4), np.float32)
for y in range(H * 2):
    for x in range(W * 2):
        # LINEAR sampling at output pixel centers, with CLAMP_TO_EDGE.
        fx, fy = (x + .5) / 2 - .5, (y + .5) / 2 - .5
        ix, iy = int(np.floor(fx)), int(np.floor(fy))
        ax, ay = fx - ix, fy - iy
        sample = np.zeros(4, np.float32)
        for ox, oy, weight in [(0, 0, (1-ax)*(1-ay)), (1, 0, ax*(1-ay)), (0, 1, (1-ax)*ay), (1, 1, ax*ay)]:
            sample += main[np.clip(iy+oy, 0, H-1), np.clip(ix+ox, 0, W-1)] * weight
        output[y, x] = sample + last[y // 2, x // 2, (y % 2) * 2 + x % 2]
output = output.astype(np.float16).astype(np.float32)
c = np.clip(output[:, :, :3], 0, 1)
decoded = np.where(c < .0812, c / 4.5, np.power((c + .099) / 1.099, 1/.45))
rgba = np.concatenate([decoded, np.ones((H*2, W*2, 1), np.float32)], axis=2)
golden = np.clip(np.rint(rgba * 255), 0, 255).astype(np.uint8)
OUT.mkdir(parents=True, exist_ok=True)
(OUT / 'cnn-medium-input-8x8.rgba').write_bytes(pixels.tobytes())
(OUT / 'cnn-medium-reference-16x16.rgba').write_bytes(golden.tobytes())
print(f'Wrote {pixels.nbytes}-byte input and {golden.nbytes}-byte independent CNN output')
print(f'Signed intermediate min={min(float(a.min()) for a in textures.values()):.6f}')


def bilinear(image, width, height, dx=0.0, dy=0.0):
    """OpenGL normalized LINEAR sampling with source-pixel offsets and clamp-to-edge."""
    ih, iw = image.shape[:2]
    xx, yy = np.meshgrid((np.arange(width) + .5) * iw / width - .5 + dx,
                         (np.arange(height) + .5) * ih / height - .5 + dy)
    ix, iy = np.floor(xx).astype(int), np.floor(yy).astype(int)
    ax, ay = (xx - ix).astype(np.float32)[..., None], (yy - iy).astype(np.float32)[..., None]
    a = image[np.clip(iy, 0, ih-1), np.clip(ix, 0, iw-1)]
    b = image[np.clip(iy, 0, ih-1), np.clip(ix+1, 0, iw-1)]
    c = image[np.clip(iy+1, 0, ih-1), np.clip(ix, 0, iw-1)]
    d = image[np.clip(iy+1, 0, ih-1), np.clip(ix+1, 0, iw-1)]
    return (a * (1-ax) + b * ax) * (1-ay) + (c * (1-ax) + d * ax) * ay


def gan_reference(factor, model, count):
    """Independent graph semantics for GAN dense skips and fractional resampling."""
    chunks = read_anime4k_source(f'Anime4K_Upscale_GAN_x{factor}_{model}.glsl').split('//!DESC ')[1:]
    assert len(chunks) == count
    layers = {'MAIN': main}
    negative_min = 0.
    for chunk in chunks:
        save = re.search(r'^//!SAVE (\w+)', chunk, re.M).group(1)
        def dimension(axis):
            expression = re.search(r'^//!' + axis + r' (.+)', chunk, re.M).group(1)
            stack = []
            for token in expression.split():
                if token == '*':
                    right, left = stack.pop(), stack.pop(); stack.append(right * left)
                elif token.endswith(('.w', '.h')):
                    name, dim = token.rsplit('.', 1); stack.append(layers[name].shape[1 if dim == 'w' else 0])
                else: stack.append(float(token))
            assert len(stack) == 1
            return int(stack[0])
        width, height = dimension('WIDTH'), dimension('HEIGHT')
        helpers = {}
        for match in re.finditer(r'^#define (g(?:o)?_\d+)(?:\(x_off, y_off\))? (.+)$', chunk, re.M):
            name, expr = match[1], match[2]
            source = re.search(r'(\w+)_tex(?:Off)?\(', expr).group(1)
            offset_scale = re.search(r'vec2\(x_off, y_off\)\s*\*\s*([\d.]+)', expr)
            helpers[name] = (source, 'max(' in expr, 'max(-' in expr,
                             float(offset_scale[1]) if offset_scale else 1.)
        result = np.zeros((height, width, 4), np.float32)
        terms = 0
        for match in re.finditer(r'mat4\(([^)]+)\)\s*\*\s*(g(?:o)?_\d+)(?:\(([-\d.]+),\s*([-\d.]+)\))?', chunk):
            weights = np.array([float(n) for n in match[1].split(',')], np.float32).reshape(4, 4).T
            source, relu, negative, scale = helpers[match[2]]
            dx, dy = (float(match[3]) * scale, float(match[4]) * scale) if match[3] else (0., 0.)
            sampled = bilinear(layers[source], width, height, dx, dy)
            if negative: sampled = -sampled
            if relu: sampled = np.maximum(sampled, 0)
            result += sampled @ weights.T
            terms += 1
        assert terms and terms == chunk.count('mat4(')
        bias = re.findall(r'result\s*\+=\s*vec4\(([^)]+)\)', chunk)[-1]
        result += np.array([float(n) for n in bias.split(',')], np.float32)
        if 'return result + MAIN_tex(MAIN_pos);' in chunk:
            result += bilinear(layers['MAIN'], width, height)
        else:
            assert 'return result;' in chunk
        layers[save] = result.astype(np.float16).astype(np.float32)
        negative_min = min(negative_min, float(layers[save].min()))
    result = layers['MAIN']
    assert result.shape == (H * factor, W * factor, 4)
    c = np.clip(result[:, :, :3], 0, 1)
    decoded = np.where(c < .0812, c / 4.5, np.power((c + .099) / 1.099, 1/.45))
    rgba = np.concatenate([decoded, np.ones((H*factor, W*factor, 1), np.float32)], axis=2)
    golden = np.clip(np.rint(rgba * 255), 0, 255).astype(np.uint8)
    path = OUT / f'gan-{factor}x-reference-{W*factor}x{H*factor}.rgba'
    path.write_bytes(golden.tobytes())
    print(f'{path.name}: {count} stages, {golden.nbytes} bytes, signed min={negative_min:.6f}')


gan_reference(3, 'L', 30)
gan_reference(4, 'UUL', 84)
