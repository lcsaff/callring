package com.greatwall.callring

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract

/** Opt-in device proof only. The system picker grants access to one explicitly selected tree. */
class RealRecordingGrantActivity : Activity() {
    private lateinit var expectedTree: Uri
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        expectedTree = requireNotNull(intent.data)
        require(expectedTree.scheme == "content" && DocumentsContract.isTreeUri(expectedTree))
        if (state == null) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUriUsingTree(expectedTree, DocumentsContract.getTreeDocumentId(expectedTree)))
        }, 1)
    }

    @Deprecated("Legacy instrumentation uses Activity results")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        try {
            check(requestCode == 1 && resultCode == RESULT_OK && data?.data == expectedTree) { "qa.recording_tree_not_selected" }
            contentResolver.takePersistableUriPermission(expectedTree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } finally { finish() }
    }
}
