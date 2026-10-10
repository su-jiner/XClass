package cn.xiao.xyaax.update.security

object UpdateSecurityConfig {
    const val EXPECTED_PACKAGE_NAME = "cn.xiao.xyaax"

    /**
     * SHA-256 fingerprint of the official XClass release signing certificate
     * (keystore alias `XiaoXiao`, generated 2026-10-10, PKCS12).
     */
    const val OFFICIAL_SIGNER_SHA256 = "c385ae7041b3b5166bdd4ee9761e26d0e1c379349fa94c27365b47ba9093cebc"

    /**
     * Map of keyId to Base64-encoded RSA SubjectPublicKeyInfo (X.509 DER).
     */
    val TRUSTED_PUBLIC_KEYS = mapOf(
        "main-2026" to "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAll+rSRVfCVNx9wMEfmqrQKckQLjJopGsET73pWad5c8vGyUDrBkS/VMcuh7csLWNYYRY1QiM+6s7S8x38kBRM1iiZw/QXKSQ/XRqmSfb3YyXOUxUeHTrVv7/4nLN2OLq9WrjcKPc4PVykwKm2Xdy6LYQ5xPwQ+QUbJQYPNFnZv0gVcmgZoN2Fg0lyj/x43pI2081asz9V5Mtm5JBgfs+0Z1+eoNrDeHPpxCw4yqMSEM95GrXffmeDy0WsCTVijyW8b0L5xZNhdFkjR43zMxWZ1ZERPbR5jvXfQSyfrgECZ+GsjgyfuAIid00X07OiQqspo9qEQcWNGbTW2nmFReBZwIDAQAB"
    )

    /**
     * 更新链路已启用：客户端下载、验签、安装链路完整（设置页版本行可手动检查更新）。
     * 稳定版清单与 APK 均发布在 GitHub Release（打 v* tag 时 CI 自动签名构建并生成签名清单）。
     */
    val DEFAULT_ENDPOINTS: List<String> = listOf(
        "https://github.com/su-jiner/XClass/releases/latest/download/stable.json"
    )
}
