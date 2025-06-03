import sys
import os
import re
import struct

WIDTH = 10_000
BYTES_PER_PIXEL = 3
RAW_ROW_BYTES = WIDTH * BYTES_PER_PIXEL
PADDING = (4 - (RAW_ROW_BYTES % 4)) % 4  
ROW_STRIDE = RAW_ROW_BYTES + PADDING
BMP_HEADER_BYTES = 14 + 40

SOLID_COLORS = [
    (255, 0,   0),    
    (0,   255, 0),    
    (0,   0,   255),  
    (255, 255, 0),    
]

UNIT_MULTIPLIERS = {
    'B':  1,
    'KB': 1024,
    'MB': 1024 ** 2,
    'GB': 1024 ** 3,
    'TB': 1024 ** 4,
}

def parse_size(size_str: str) -> int:
    size_str = size_str.strip().upper()
    m = re.fullmatch(r'(\d+)(B|KB|MB|GB|TB)?', size_str)
    if not m:
        raise ValueError(f"Invalid size specifier '{size_str}'. Use formats like '1MB', '100kb', '42'.")
    number = int(m.group(1))
    unit = m.group(2) or 'B'
    multiplier = UNIT_MULTIPLIERS.get(unit, None)
    if multiplier is None:
        raise ValueError(f"Unsupported unit '{unit}'. Use B, KB, MB, GB, or TB.")
    return number * multiplier


def build_bmp_headers(file_size: int, width: int, height: int) -> (bytes, bytes):
    bfType      = b'BM'                     
    bfSize      = file_size                 
    bfReserved1 = 0                         
    bfReserved2 = 0                         
    bfOffBits   = BMP_HEADER_BYTES         

    bmp_header = struct.pack(
        '<2sIHHI',
        bfType,
        bfSize,
        bfReserved1,
        bfReserved2,
        bfOffBits
    )

    biSize          = 40
    biWidth         = width
    biHeight        = height
    biPlanes        = 1
    biBitCount      = 24
    biCompression   = 0
    biSizeImage     = ROW_STRIDE * height
    biXPelsPerMeter = 0
    biYPelsPerMeter = 0
    biClrUsed       = 0
    biClrImportant  = 0

    dib_header = struct.pack(
        '<IIIHHIIIIII',
        biSize,
        biWidth,
        biHeight,
        biPlanes,
        biBitCount,
        biCompression,
        biSizeImage,
        biXPelsPerMeter,
        biYPelsPerMeter,
        biClrUsed,
        biClrImportant
    )

    return bmp_header, dib_header


def create_bmp_with_bands(output_path: str, target_bytes: int):
    usable_for_pixels = target_bytes - BMP_HEADER_BYTES
    if usable_for_pixels < ROW_STRIDE:
        height = 1
        sys.stderr.write(
            f"Warning: requested size ({target_bytes} bytes) is smaller than minimum BMP (~"
            f"{BMP_HEADER_BYTES + ROW_STRIDE} bytes). Generating a 1-row image → file "
            f"will be {BMP_HEADER_BYTES + ROW_STRIDE:,} bytes instead.\n"
        )
    else:
        height = usable_for_pixels // ROW_STRIDE
        if height < 1:
            height = 1

    actual_pixel_bytes = ROW_STRIDE * height
    actual_file_size = BMP_HEADER_BYTES + actual_pixel_bytes

    bmp_header, dib_header = build_bmp_headers(actual_file_size, WIDTH, height)

    row_buffers = []
    for (r, g, b) in SOLID_COLORS:
        pixel_triplet = bytes((b, g, r))
        row_data = pixel_triplet * WIDTH
        if PADDING:
            row_data += b'\x00' * PADDING
        row_buffers.append(row_data)

    num_bands = len(SOLID_COLORS)
    if height < num_bands:
        band_height = 1
    else:
        band_height = height // num_bands

    with open(output_path, 'wb') as f:
        f.write(bmp_header)
        f.write(dib_header)
        for row in range(height):
            if band_height == 1:
                
                band_index = min(row, num_bands - 1)
            else:
                band_index = min(row // band_height, num_bands - 1)
            f.write(row_buffers[band_index])

    
    print(f"✔ Created '{output_path}' → {actual_file_size:,} bytes "
          f"(target was {target_bytes:,} bytes).")
    print(f"  Dimensions: {WIDTH}x{height} pixels")
    print(f"  Pixel data: {actual_pixel_bytes:,} bytes "
          f"({ROW_STRIDE:,} bytes/row x {height:,} rows)")
    print(f"  Header:     {BMP_HEADER_BYTES} bytes (14 + 40)")
    if actual_file_size > target_bytes:
        print(f"⚠ Note: Actual size exceeds requested size by "
              f"{actual_file_size - target_bytes:,} bytes.")


def main():
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} <SIZE>\n"
              f"  Where <SIZE> is like '1MB', '256KB', '2GB', etc.", file=sys.stderr)
        sys.exit(1)

    size_arg = sys.argv[1]
    
    try:
        target_bytes = parse_size(size_arg)
    except ValueError as ve:
        print(f"Error: {ve}", file=sys.stderr)
        sys.exit(2)

    output_filename = f"{size_arg}.bmp"
    
    if os.path.exists(output_filename):
        print(f"Warning: '{output_filename}' already exists; it will be overwritten.", file=sys.stderr)

    try:
        create_bmp_with_bands(output_filename, target_bytes)
    except Exception as e:
        print(f"Error during BMP generation: {e}", file=sys.stderr)
        sys.exit(3)


if __name__ == "__main__":
    main()
