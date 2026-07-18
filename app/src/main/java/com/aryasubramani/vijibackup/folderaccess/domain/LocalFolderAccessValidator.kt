package com.aryasubramani.vijibackup.folderaccess.domain

fun interface LocalFolderAccessValidator {
    suspend fun validate(treeUri: String): FolderAccessHealth
}
