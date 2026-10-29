"""Local image fixes for dim camera frames, applied before the model sees them.

  auto: stretch contrast, then gamma-lift dark frames toward a mid-grey average.
  eq:   histogram equalization (stronger; can amplify noise).
"""
import io
import math

from PIL import Image, ImageOps, ImageStat

TARGET_MEAN = 0.45


def enhance(jpeg, method):
    img = Image.open(io.BytesIO(jpeg)).convert("RGB")
    if method == "auto":
        img = ImageOps.autocontrast(img, cutoff=1)
        mean = ImageStat.Stat(img.convert("L")).mean[0] / 255
        if 0.02 < mean < TARGET_MEAN:
            gamma = math.log(TARGET_MEAN) / math.log(mean)
            img = img.point(lambda v: round(255 * (v / 255) ** gamma))
    elif method == "eq":
        img = ImageOps.equalize(img)
    else:
        raise ValueError(f"unknown method {method}")
    out = io.BytesIO()
    img.save(out, "JPEG", quality=90)
    return out.getvalue()
