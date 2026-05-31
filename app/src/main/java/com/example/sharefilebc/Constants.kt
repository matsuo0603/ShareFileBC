package com.example.sharefilebc

/**
 * アプリ全体で使用する定数定義
 * Swift版のConstants.swiftに相当
 */
object Constants {

    object Strings {
        /**
         * トークンのColorID
         * 論文 p.32 およびSwift版のConstants.Strings.tokenColorIdに対応
         *
         * Swift版のdefaultTokenColorIdと同じ値
         */
        const val tokenColorId = "c12b2db6b80ce0c8d4e3b35f73bdf984b979601ae283d0f95790f012ad3d6f36c2"

        /**
         * BIP32派生パス
         * Swift版のConstants.Strings.bip32Pathに対応
         */
        const val bip32Path = "m/44'/0'/0'/0/0"
    }

    object Timing {
        /**
         * トースト表示時間（ミリ秒）
         */
        const val toastDuration = 3000L
    }
}