"""Widen access flags on a copy of the vanilla client jar.

Forge applies access transformers at runtime, so a member that is public in-game
is still `protected` in client-<ver>-official.jar. Compiling against the raw jar
therefore fails on those members. This script rewrites the class files so javac
sees the same modifiers the game will have at runtime.

Usage:  python at_patch.py <src-jar> <dst-jar> <spec.json>

spec.json:
  {
    "members": [ { "class": "net/minecraft/...", "name": "memberName", "kind": "field"|"method" } ]
  }
"""
import json
import os
import sys
import zipfile

ACC_PUBLIC = 0x0001
ACC_PRIVATE = 0x0002
ACC_PROTECTED = 0x0004

# Constant pool sizes (bytes of payload after the tag byte) keyed by tag.
CP_SIZES = {
    3: 4, 4: 4, 5: 8, 6: 8,
    7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4,
    15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2,
}
CP_UTF8 = 1


def parse_pool(data):
    """Return (utf8{idx:str}, class_name_index{idx:int}, pool_end)."""
    cp_count = int.from_bytes(data[8:10], "big")
    pos = 10
    utf8 = {}
    class_name_index = {}
    index = 1
    while index < cp_count:
        tag = data[pos]
        pos += 1
        if tag == CP_UTF8:
            length = int.from_bytes(data[pos:pos + 2], "big")
            pos += 2
            utf8[index] = data[pos:pos + length].decode("utf-8", "replace")
            pos += length
        elif tag == 7:
            class_name_index[index] = int.from_bytes(data[pos:pos + 2], "big")
            pos += 2
        else:
            size = CP_SIZES.get(tag)
            if size is None:
                raise ValueError(f"unknown constant pool tag {tag} at {pos - 1}")
            pos += size
            if tag in (5, 6):  # long / double occupy two slots
                index += 1
        index += 1
    return utf8, class_name_index, pos


def patched_class(data, class_name, member_specs, changed):
    out = bytearray(data)
    utf8, class_name_index, pos = parse_pool(data)

    def name_of(idx):
        return utf8.get(class_name_index.get(idx), "")

    pos += 2                    # access_flags
    this_class = int.from_bytes(data[pos:pos + 2], "big")
    actual_name = name_of(this_class)
    pos += 2                    # this_class
    pos += 2                    # super_class
    iface_count = int.from_bytes(data[pos:pos + 2], "big")
    pos += 2 + iface_count * 2

    for kind, kind_specs in (("field", [m for m in member_specs if m["kind"] == "field"]),
                             ("method", [m for m in member_specs if m["kind"] == "method"])):
        count = int.from_bytes(data[pos:pos + 2], "big")
        pos += 2
        for _ in range(count):
            access_pos = pos
            access = int.from_bytes(data[access_pos:access_pos + 2], "big")
            name_idx = int.from_bytes(data[pos + 2:pos + 4], "big")
            member_name = utf8.get(name_idx, "")
            pos += 4            # access_flags, name_index
            pos += 2            # descriptor_index
            attr_count = int.from_bytes(data[pos:pos + 2], "big")
            pos += 2
            for _a in range(attr_count):
                pos += 2        # attribute_name_index
                length = int.from_bytes(data[pos:pos + 4], "big")
                pos += 4 + length
            for spec in kind_specs:
                if spec["name"] != member_name:
                    continue
                new_access = (access & ~(ACC_PRIVATE | ACC_PROTECTED)) | ACC_PUBLIC
                if new_access != access:
                    out[access_pos:access_pos + 2] = new_access.to_bytes(2, "big")
                    changed.append(f"{actual_name}.{member_name}() -> public")
    return bytes(out)


def add_interfaces(data, ifaces, class_name, changed):
    """Append Class entries to the constant pool and register them in interfaces[]."""
    utf8, class_name_index, pool_end = parse_pool(data)
    existing = {name for name in class_name_index.values()}
    existing_names = {utf8.get(idx, "") for idx in existing}

    cp_count = int.from_bytes(data[8:10], "big")
    pos = pool_end
    pos += 2                                    # access_flags
    this_class_idx = int.from_bytes(data[pos:pos + 2], "big")
    pos += 2                                    # this_class
    pos += 2                                    # super_class
    iface_count_pos = pos
    iface_count = int.from_bytes(data[pos:pos + 2], "big")
    iface_start = pos + 2
    iface_end = iface_start + iface_count * 2

    to_add = [i for i in ifaces if i not in existing_names]
    if not to_add:
        return data

    new_utf8 = []
    new_class = []
    next_index = cp_count
    for iface in to_add:
        utf8_index = next_index
        new_utf8.append((utf8_index, iface))
        new_class.append((utf8_index + 1, utf8_index))
        next_index += 2

    added = bytearray()
    for _idx, text in new_utf8:
        encoded = text.encode("utf-8")
        added.append(CP_UTF8)
        added += len(encoded).to_bytes(2, "big")
        added += encoded
    for _idx, name_idx in new_class:
        added.append(7)
        added += name_idx.to_bytes(2, "big")

    new_count = cp_count + len(to_add) * 2
    out = bytearray()
    out += data[:8]
    out += new_count.to_bytes(2, "big")
    out += data[10:pool_end]
    out += added

    # body follows; interfaces table offset shifts by len(added)
    body = bytearray(data[pool_end:])
    shift = len(added)
    new_iface_count = iface_count + len(to_add)
    body[0:0] = b""  # no-op keeps flake quiet
    body[iface_count_pos - pool_end:iface_count_pos - pool_end + 2] = new_iface_count.to_bytes(2, "big")
    extra_refs = b"".join(idx.to_bytes(2, "big") for idx, _n in new_class)
    insert_at = iface_end - pool_end
    body[insert_at:insert_at] = extra_refs
    out += body
    for iface in to_add:
        changed.append(f"{class_name} implements {iface}")
    return bytes(out)


def main(argv):
    if len(argv) not in (4, 5):
        print(__doc__)
        return 2
    src, dst, spec_path = argv[1], argv[2], argv[3]
    layers = argv[4].split(";") if len(argv) == 5 and argv[4] else []
    spec = json.load(open(spec_path, encoding="utf-8"))
    # spec["implements"] is { "iface": forge interface, "class": vanilla class };
    # Forge layers the interface onto the class at runtime, so for compiling we
    # copy the interface class file next to the vanilla one.
    iface_names = {entry["iface"] for entry in spec.get("implements", [])}
    patch_ifaces = {}
    for entry in spec.get("implements", []):
        patch_ifaces.setdefault(entry["class"], []).append(entry["iface"])
    targets = {}
    for entry in spec.get("members", []):
        targets.setdefault(entry["class"], []).append(entry)

    os.makedirs(os.path.dirname(dst), exist_ok=True)
    changed = []
    extra = {}
    for layer in layers:
        if not os.path.exists(layer):
            continue
        with zipfile.ZipFile(layer) as lz:
            for name in lz.namelist():
                if name in extra or not name.endswith(".class"):
                    continue
                if name[:-6] in iface_names:
                    extra[name] = lz.read(name)

    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        existing = set()
        for item in zin.infolist():
            existing.add(item.filename)
            data = zin.read(item.filename)
            if item.filename.endswith(".class"):
                name = item.filename[:-6]
                if name in targets:
                    data = patched_class(data, name, targets[name], changed)
                if name in patch_ifaces:
                    data = add_interfaces(data, patch_ifaces[name], name, changed)
            zout.writestr(item, data)
        for name, data in extra.items():
            if name not in existing:
                zout.writestr(name, data)

    for line in changed:
        print(f"  {line}")
    print(f"patched {len(changed)} member(s) -> {dst}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
