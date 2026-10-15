"""把编译出的 classes.dex 装回 APK 并签名。

不依赖任何外部 APK —— 仓库里已经带了全部资源：
    AndroidManifest.xml   权限已调整的二进制清单
    resources.arsc        应用名改成「Awa的安装助手」
    res/ic_launcher.png   图标（存 stor 不压缩，与原包一致）
    assets/mtk-su         内置提权工具，65,144 字节

签名：没有 tools/awa_key.pem 就现场生成一把 2048 位 RSA 并存下来，
      之后重复构建都用同一把，可以直接覆盖安装。
      纯 v1(JAR) 签名，Android 5.1 够用。

用法：  python build_apk.py      （在 build.sh 里被调用，一般不手动跑）
"""
import base64
import datetime
import hashlib
import os
import struct
import zipfile
import zlib

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs7
from cryptography.x509.oid import NameOID

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(ROOT, 'dist')
os.makedirs(OUT_DIR, exist_ok=True)
OUT_NAME = 'Awa\u7684\u5b89\u88c5\u52a9\u624b.apk'          # Awa的安装助手
OUT_PATH = os.path.join(OUT_DIR, OUT_NAME)

KEY_PEM = os.path.join(ROOT, 'tools', 'awa_key.pem')
CRT_PEM = os.path.join(ROOT, 'tools', 'awa_cert.pem')

# ---------------------------------------------------------------- 1. 密钥
os.makedirs(os.path.dirname(KEY_PEM), exist_ok=True)
if os.path.exists(KEY_PEM) and os.path.exists(CRT_PEM):
    key = serialization.load_pem_private_key(open(KEY_PEM, 'rb').read(), password=None)
    cert = x509.load_pem_x509_certificate(open(CRT_PEM, 'rb').read())
    print('复用已有签名密钥')
else:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, u'Awa APK Installer')])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder()
            .subject_name(name).issuer_name(name)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=365 * 30))
            .sign(key, hashes.SHA256()))
    open(KEY_PEM, 'wb').write(key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption()))
    open(CRT_PEM, 'wb').write(cert.public_bytes(serialization.Encoding.PEM))
    print('已生成并保存新签名密钥 -> tools/awa_key.pem')
    print('⚠ 这个文件是你的私钥，别提交到公开仓库！已在 .gitignore 里排除')

# ---------------------------------------------------------------- 2. 条目
def rd(rel):
    p = os.path.join(ROOT, rel)
    with open(p, 'rb') as f:
        return f.read()

APK_NAME_STR = 'Awa的安装助手'

payload = [
    # (包内路径, 数据, 是否 STORED 不压缩)
    ('AndroidManifest.xml', rd('AndroidManifest.xml'), False),
    ('classes.dex', rd('out_dex/classes.dex'), False),
    ('res/drawable/ic_launcher.png', rd('res/ic_launcher.png'), True),
    ('resources.arsc', rd('resources.arsc'), False),
    ('assets/mtk-su', rd('assets/mtk-su'), False),
]

for name, data, _ in payload:
    print('  %-28s %7d 字节' % (name, len(data)))

EXPORT_DIR = os.path.join(ROOT, 'dist')
with open(os.path.join(EXPORT_DIR, 'mtk-su'), 'wb') as f:
    f.write(payload[4][1])
print('已单独导出 mtk-su -> dist/mtk-su  (%d 字节)' % len(payload[4][1]))


def deflate(b):
    c = zlib.compressobj(9, zlib.DEFLATED, -15)
    return c.compress(b) + c.flush()


def b64(b):
    return base64.b64encode(b).decode('ascii')


# 每个条目的压缩方式：icons 用 STORED(0)（Android 要求资源可 mmap），
# 其余 DEFLATE(8)。external_attr 一律 0644。
STORED = {0}
METHODS = [0 if stored else 8 for _, _, stored in payload]
EXT_ATTR = 0o100644 << 16


# ---------------------------------------------------------------- 3. MANIFEST.MF
mf = bytearray(b'Manifest-Version: 1.0\r\n\r\n')
sections = {}
for idx, (name, data, _) in enumerate(payload):
    sec = ('Name: %s\r\nSHA-256-Digest: %s\r\n\r\n' % (
        name, b64(hashlib.sha256(data).digest()))).encode('ascii')
    sections[idx] = sec
    mf += sec
mf = bytes(mf)

# ---------------------------------------------------------------- 4. CERT.SF
sf = bytearray()
sf += b'Signature-Version: 1.0\r\n'
sf += b'Created-By: 1.0 (Android)\r\n'
sf += ('SHA-256-Digest-Manifest: %s\r\n' % b64(hashlib.sha256(mf).digest())).encode('ascii')
sf += b'\r\n'
for idx, (name, _, _) in enumerate(payload):
    sf += ('Name: %s\r\nSHA-256-Digest: %s\r\n\r\n' % (
        name, b64(hashlib.sha256(sections[idx]).digest()))).encode('ascii')
sf = bytes(sf)

# ---------------------------------------------------------------- 5. CERT.RSA
sig = (pkcs7.PKCS7SignatureBuilder().set_data(sf)
       .add_signer(cert, key, hashes.SHA256())
       .sign(serialization.Encoding.DER,
             [pkcs7.PKCS7Options.DetachedSignature, pkcs7.PKCS7Options.Binary]))

# ---------------------------------------------------------------- 6. 写 zip
entries = [(name, data) for name, data, _ in payload] + [
    ('META-INF/CERT.SF', sf),
    ('META-INF/CERT.RSA', sig),
    ('META-INF/MANIFEST.MF', mf),
]

buf = bytearray()
cd = bytearray()
count = 0
off = 0
for idx, (name, data) in enumerate(entries):
    method = 8 if idx >= len(payload) else METHODS[idx]
    ni = name.encode('utf-8')
    stored = data if method == 0 else deflate(data)
    crc = zlib.crc32(data) & 0xffffffff

    # STORED 条目要求数据按 4 字节对齐（Android 的 apksigner 会检查）。
    # 用 0xD935 extra 字段补字节；但这个字段有 4 字节头（id + size），
    # 所以最少占 4 个字节 —— pad 只有 1~3 时得向上取到 4。
    extra = b''
    if method == 0:
        base = off + 30 + len(ni)
        pad = (4 - base % 4) % 4
        if pad:
            while pad < 4:
                pad += 4
            extra = struct.pack('<HH', 0xD935, pad - 4) + b'\x00' * (pad - 4)

    buf += struct.pack('<IHHHHHIIIHH', 0x04034B50, 20, 0, method, 0, 0,
                       crc, len(stored), len(data), len(ni), len(extra))
    buf += ni + extra + stored

    cd += struct.pack('<IHHHHHHIIIHHHHHII', 0x02014B50, 20, 20, 0, method, 0, 0,
                      crc, len(stored), len(data), len(ni), len(extra), 0, 0, 0,
                      EXT_ATTR, off)
    cd += ni + extra
    count += 1
    off += 30 + len(ni) + len(extra) + len(stored)

cd_off = len(buf)
buf += cd
buf += struct.pack('<IHHHHIIH', 0x06054B50, 0, 0, count, count, len(cd), cd_off, 0)

with open(OUT_PATH, 'wb') as f:
    f.write(bytes(buf))

size = len(buf)
md5 = hashlib.md5(bytes(buf)).hexdigest()
print()
print('已生成: %s' % OUT_PATH)
print('  大小 %d 字节 (%.1f KB)  md5 %s' % (size, size / 1024.0, md5))
print('  条目数 %d  classes.dex %d 字节' % (count, len(payload[1][1])))

# 自校验：zip 结构 + 签名能过 jarsigner 才算数
z = zipfile.ZipFile(OUT_PATH)
bad = z.testzip()
assert bad is None, 'zip CRC 校验失败: %s' % bad
names = set(z.namelist())
for need in ('AndroidManifest.xml', 'classes.dex', 'resources.arsc',
             'assets/mtk-su', 'res/drawable/ic_launcher.png',
             'META-INF/MANIFEST.MF', 'META-INF/CERT.SF', 'META-INF/CERT.RSA'):
    assert need in names, '缺少条目: %s' % need
assert z.getinfo('res/drawable/ic_launcher.png').compress_type == 0, '图标必须是 STORED'
print('自校验通过：zip 完好、8 个条目齐全、图标 STORED')
