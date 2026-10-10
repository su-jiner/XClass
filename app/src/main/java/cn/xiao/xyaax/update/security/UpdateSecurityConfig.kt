package cn.xiao.xyaax.update.security

object UpdateSecurityConfig {
    const val EXPECTED_PACKAGE_NAME = "cn.xiao.xyaax"

    /**
     * SHA-256 fingerprint of the official XClass release signing certificate
     * (keystore alias `XiaoXiao`, generated 2026-10).
     */
    const val OFFICIAL_SIGNER_SHA256 = "4c45560b69c1c7d1fb400a17ea5b0b9cd841fb1ab8dd9d4ef65357e78ecef3bf"

    /**
     * Map of keyId to Base64-encoded RSA SubjectPublicKeyInfo (X.509 DER).
     */
    val TRUSTED_PUBLIC_KEYS = mapOf(
        "main-2026" to "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAs4DRtdfr41/P4Dhiewy2YskgzmtKWYyeWukANtm8oIfQMd8uI3qt2YGnva8oXxwK4abA7ua+/xUx8+kw1+hGNdbOFQXXnZwr7N2KksjzBYKNk8/cXYYv59eSShCezCZyguz/Bw9BeGCeIqvc2t1NqIMMCbGTEuqK0RJLqfAxkJlRMaJwNvPY1PhhWbJodx5V+xQCgw1zdKNeK6o749J3aFjcWf2LfJ8bhbuzV7royKzIUuOkIiM5IA3bGQuDKZxx0q6ehkesgcsf/5xmNwdsBVH0oBW52AaGMGSA9t0wE3kRsul4Bgi2PidWzL9M56xhoxJuGQHUWwhswwR83oYFDQIDAQAB"
    )

    /**
     * 更新源暂未启用：发布渠道（GitHub Release / 自建镜像）确定后再填入清单地址。
     * 客户端下载、验签、安装链路与单元测试均保留，空列表即“不检查更新”。
     */
    val DEFAULT_ENDPOINTS: List<String> = emptyList()
}
