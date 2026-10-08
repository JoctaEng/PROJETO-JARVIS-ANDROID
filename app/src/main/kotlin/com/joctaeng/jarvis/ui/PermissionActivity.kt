package com.joctaeng.jarvis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Janela transparente que só pede uma permissão do Android e se fecha. Usada pelas ferramentas (agenda, contatos...):
 * se a permissão faltar, o pedido aparece sozinho na tela, sem a pessoa precisar procurar em Meu Euno.
 */
class PermissionActivity : ComponentActivity() {
    private val launcher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permission = intent.getStringExtra(EXTRA_PERMISSION)
        if (permission == null) finish() else launcher.launch(permission)
    }

    companion object {
        private const val EXTRA_PERMISSION = "permission"

        fun intent(context: Context, permission: String): Intent =
            Intent(context, PermissionActivity::class.java).putExtra(EXTRA_PERMISSION, permission).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
