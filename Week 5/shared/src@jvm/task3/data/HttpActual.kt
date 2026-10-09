package task3.data

actual fun deleteFile(path: String) {
    java.io.File(path).delete()
}
