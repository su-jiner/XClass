import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.security.cert.Certificate;
import java.security.KeyFactory;
import java.util.Base64;

/**
 * XClass 更新清单签名工具（纯 JDK，无外部依赖）。
 *
 * 用法：
 *   java SignManifest exportkey <keystore> <storepass> <alias>
 *       -> 输出该别名证书 SubjectPublicKeyInfo 的 Base64（填入 UpdateSecurityConfig.TRUSTED_PUBLIC_KEYS）
 *   java SignManifest sign <payloadJsonPath> <keystore> <storepass> <keypass> <alias> <keyId> <outPath>
 *       -> 读 payload JSON 原始字节，SHA256withRSA 签名，输出信封 {schema,keyId,payload(Base64URL),signature(Base64)}
 *   java SignManifest verify <envelopePath> <trustedPublicKeyBase64>
 *       -> 校验信封签名（与客户端 UpdateManifestVerifier 相同算法）
 */
public class SignManifest {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        if (mode.equals("exportkey")) {
            if (args.length < 4) throw new IllegalArgumentException("exportkey <keystore> <storepass> <alias>");
            KeyStore ks = load(args[1], args[2]);
            Certificate cert = ks.getCertificate(args[3]);
            PublicKey pub = cert.getPublicKey();
            byte[] spki = pub.getEncoded();
            String b64 = Base64.getEncoder().encodeToString(spki);
            System.out.println(b64);
            // 同时输出证书 SHA-256（供 OFFICIAL_SIGNER_SHA256 核对）
            byte[] der = cert.getEncoded();
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(der);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            System.out.println("CERT_SHA256=" + sb);
        } else if (mode.equals("sign")) {
            if (args.length < 8) throw new IllegalArgumentException(
                "sign <payloadJsonPath> <keystore> <storepass> <keypass> <alias> <keyId> <outPath>");
            String payloadPath = args[1], ksPath = args[2], storePass = args[3], keyPass = args[4];
            String alias = args[5], keyId = args[6], outPath = args[7];
            byte[] payloadBytes = java.nio.file.Files.readAllBytes(new File(payloadPath).toPath());
            KeyStore ks = load(ksPath, storePass);
            PrivateKey priv = (PrivateKey) ks.getKey(alias, keyPass.toCharArray());
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(priv);
            signer.update(payloadBytes);
            byte[] signature = signer.sign();
            String payloadB64url = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes);
            String signatureB64 = Base64.getEncoder().encodeToString(signature);
            String envelope = "{\"schema\":1,\"keyId\":\"" + keyId + "\",\"payload\":\"" + payloadB64url
                + "\",\"signature\":\"" + signatureB64 + "\"}";
            try (FileOutputStream out = new FileOutputStream(outPath)) {
                out.write(envelope.getBytes(StandardCharsets.UTF_8));
            }
            System.out.println("WROTE " + outPath);
        } else if (mode.equals("verify")) {
            if (args.length < 3) throw new IllegalArgumentException("verify <envelopePath> <trustedPublicKeyBase64>");
            String envelopeJson = new String(java.nio.file.Files.readAllBytes(new File(args[1]).toPath()), StandardCharsets.UTF_8);
            String pubB64 = args[2].replace("\n", "").replace("\r", "").trim();
            // 极简信封解析（无 JSON 库）：取 payload 与 signature 字段
            java.util.regex.Matcher mP = java.util.regex.Pattern.compile("\"payload\"\\s*:\\s*\"([^\"]*)\"").matcher(envelopeJson);
            java.util.regex.Matcher mS = java.util.regex.Pattern.compile("\"signature\"\\s*:\\s*\"([^\"]*)\"").matcher(envelopeJson);
            if (!mP.find() || !mS.find()) throw new IllegalArgumentException("envelope missing payload/signature");
            String payloadB64url = mP.group(1);
            String signatureB64 = mS.group(1);
            byte[] payloadBytes = Base64.getUrlDecoder().decode(payloadB64url);
            byte[] signatureBytes = Base64.getDecoder().decode(signatureB64);
            byte[] keyBytes = Base64.getDecoder().decode(pubB64);
            PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(pub);
            verifier.update(payloadBytes);
            boolean ok = verifier.verify(signatureBytes);
            System.out.println(ok ? "VERIFY OK" : "VERIFY FAILED");
            System.exit(ok ? 0 : 1);
        } else {
            throw new IllegalArgumentException("unknown mode: " + mode);
        }
    }

    private static KeyStore load(String path, String pass) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(path)) {
            ks.load(in, pass.toCharArray());
        }
        return ks;
    }
}
