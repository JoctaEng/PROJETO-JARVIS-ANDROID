"""Gera a keystore de assinatura do Euno a partir de uma frase secreta.

A mesma frase produz sempre a mesma chave e o mesmo certificado (chave EC P-256 derivada por HKDF,
certificado com datas fixas e assinatura ECDSA determinística). Assim o CI assina todo APK com a
mesma identidade sem que o arquivo da chave fique no repositório, que é público.

Uso: EUNO_SIGNING_SEED="frase" python tools/assinatura/gerar_keystore.py saida.p12 senha
"""
import datetime
import os
import sys

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

P256_ORDER = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551


def main(out_path: str, password: str) -> None:
    seed = os.environ.get("EUNO_SIGNING_SEED", "")
    if len(seed) < 16:
        sys.exit("EUNO_SIGNING_SEED ausente ou curta demais (mínimo 16 caracteres)")
    material = HKDF(algorithm=hashes.SHA256(), length=48, salt=b"euno-apk-signing-v1", info=b"p256").derive(seed.encode())
    scalar = int.from_bytes(material, "big") % (P256_ORDER - 1) + 1
    key = ec.derive_private_key(scalar, ec.SECP256R1())

    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Euno"), x509.NameAttribute(NameOID.ORGANIZATION_NAME, "JoctaEng")])
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(1)
        .not_valid_before(datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc))
        .not_valid_after(datetime.datetime(2076, 1, 1, tzinfo=datetime.timezone.utc))
        .sign(key, hashes.SHA256(), ecdsa_deterministic=True)
    )
    data = pkcs12.serialize_key_and_certificates(
        b"euno", key, cert, None, serialization.BestAvailableEncryption(password.encode()),
    )
    with open(out_path, "wb") as f:
        f.write(data)
    print("SHA-256 do certificado:", cert.fingerprint(hashes.SHA256()).hex(":").upper())


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
