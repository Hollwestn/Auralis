"""Validate the actual minified APK's JNI target, including its ()V signature."""
import json
import struct
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as archive:
    found = None
    constructor = None
    for name in archive.namelist():
        if not name.endswith('.dex'):
            continue
        data = archive.read(name)
        def u32(offset):
            return struct.unpack_from('<I', data, offset)[0]
        def leb(offset):
            value = shift = 0
            while True:
                byte = data[offset]
                offset += 1
                value |= (byte & 127) << shift
                if byte < 128:
                    return value, offset
                shift += 7
        strings = []
        for i in range(u32(56)):
            offset = u32(u32(60) + i * 4)
            _, offset = leb(offset)
            strings.append(data[offset:data.index(b'\0', offset)].decode('utf-8', 'replace'))
        types = [strings[u32(u32(68) + i * 4)] for i in range(u32(64))]
        for i in range(u32(96)):
            offset = u32(100) + i * 32
            owner = types[u32(offset)]
            if owner not in ('Lexpo/modules/gl/GLContext;', 'Lexpo/modules/gl/GLView;'):
                continue
            cursor = u32(offset + 24)
            assert cursor, f'{owner} has no class data: R8 removed all constructors and methods'
            counts = []
            for _ in range(4):
                value, cursor = leb(cursor)
                counts.append(value)
            for _ in range(counts[0] + counts[1]):
                _, cursor = leb(cursor)
                _, cursor = leb(cursor)
            for count in counts[2:]:
                index = 0
                for _ in range(count):
                    delta, cursor = leb(cursor)
                    index += delta
                    access, cursor = leb(cursor)
                    code, cursor = leb(cursor)
                    method = u32(92) + index * 8
                    method_name = strings[u32(method + 4)]
                    if not (owner.endswith('/GLContext;') and method_name == 'flush' or owner.endswith('/GLView;') and method_name == '<init>'):
                        continue
                    proto = u32(76) + struct.unpack_from('<H', data, method + 2)[0] * 12
                    params = u32(proto + 8)
                    args = [] if not params else [types[struct.unpack_from('<H', data, params + 4 + k * 2)[0]] for k in range(u32(params))]
                    assert access & 1 and code, 'JNI target must remain public and have executable code'
                    assert types[u32(proto + 4)] == 'V'
                    if owner.endswith('/GLContext;'):
                        assert not args
                        found = {'class': 'expo.modules.gl.GLContext', 'method': 'flush', 'signature': '()V', 'public': True, 'executableCode': True, 'dex': name}
                    elif len(args) == 2 and args[0] == 'Landroid/content/Context;' and args[1].startswith('L'):
                        constructor = {'class': 'expo.modules.gl.GLView', 'method': '<init>', 'parameters': args, 'public': True, 'executableCode': True}
    assert found, 'R8 removed or renamed GLContext.flush(): Android will abort when opening GLView'
    assert constructor, 'R8 removed GLView(Context, AppContext): Expo cannot create its native view'
    found['viewConstructor'] = constructor
    print(json.dumps(found))
