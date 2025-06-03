import sys
import os

CHUNK_SIZE = 8192

def find_first_difference(path1: str, path2: str) -> tuple:
    try:
        size1 = os.path.getsize(path1)
        size2 = os.path.getsize(path2)
    except OSError as e:
        raise RuntimeError(f"Error accessing files: {e}")

    if size1 != size2:        
        differing_offset = min(size1, size2)
        
        longer_path = path2 if size2 > size1 else path1
        try:
            with open(longer_path, "rb") as f_long:
                f_long.seek(differing_offset)
                next_byte = f_long.read(1)
        except OSError as e:
            raise RuntimeError(f"Error reading from longer file: {e}")

        
        if size1 < size2:
            return (differing_offset, None, next_byte[0] if next_byte else None)
        else:
            return (differing_offset, next_byte[0] if next_byte else None, None)
    
    with open(path1, "rb") as f1, open(path2, "rb") as f2:
        offset = 0
        while True:
            b1 = f1.read(CHUNK_SIZE)
            b2 = f2.read(CHUNK_SIZE)
            
            if not b1 and not b2:
                return (None, None, None)

            if b1 != b2:                
                length = min(len(b1), len(b2))
                for i in range(length):
                    if b1[i] != b2[i]:
                        return (offset + i, b1[i], b2[i])
            
            offset += len(b1)


def human_readable_byte(b: int) -> str:
    if b is None:
        return "EOF"
    return f"0x{b:02X}"


def human_readable_offset_hex(offset: int) -> str:
    if offset is None:
        return None
    return f"0x{offset:0X}"


def main():
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} file1 file2", file=sys.stderr)
        sys.exit(2)

    path1, path2 = sys.argv[1], sys.argv[2]

    for p in (path1, path2):
        if not os.path.isfile(p):
            print(f"Error: '{p}' is not a regular file or does not exist.", file=sys.stderr)
            sys.exit(2)
        if not os.access(p, os.R_OK):
            print(f"Error: '{p}' is not readable (permission denied).", file=sys.stderr)
            sys.exit(2)

    
    try:
        offset, byte1, byte2 = find_first_difference(path1, path2)
    except RuntimeError as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(2)

    
    if offset is None:
        print("Files are identical (byte-for-byte).")
        sys.exit(0)
    else:
        hex_offset = human_readable_offset_hex(offset)
        print(f"Files differ at byte offset: {hex_offset}")
        print(f"  {path1!r} has byte: {human_readable_byte(byte1)}")
        print(f"  {path2!r} has byte: {human_readable_byte(byte2)}")
        sys.exit(1)


if __name__ == "__main__":
    main()
