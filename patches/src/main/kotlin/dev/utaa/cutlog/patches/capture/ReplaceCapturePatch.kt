package dev.utaa.cutlog.patches.capture

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import dev.utaa.cutlog.patches.shared.Constants

private const val INTERCEPTOR = "Ldev/utaa/cutlog/extension/hook/CaptureInterceptor;"
private const val REPLACEMENT = "Ldev/utaa/cutlog/extension/hook/CaptureReplacement;"
private const val HELPER_NAME = "cutlogOnCaptured"

/** CollectionCameraResult のコンストラクタ引数。難読化されない JDK / Android の型だけで照合する。 */
private val RESULT_CONSTRUCTOR_PARAMETERS = listOf(
    "Landroid/net/Uri;",
    "Ljava/lang/String;",
    "J",
    "I",
    "I",
    "I",
    "Z",
    "Ljava/lang/Long;",
    "Ljava/lang/Integer;",
    "Ljava/lang/String;",
)

private val IPUT_OPCODES = setOf(
    Opcode.IPUT,
    Opcode.IPUT_WIDE,
    Opcode.IPUT_OBJECT,
    Opcode.IPUT_BOOLEAN,
)

private val captureResultToStringFingerprint = Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
    strings = listOf("CollectionCameraResult(uri="),
)

/** 録画完了時に、固定パスの動画があれば録画結果を差し替える。 */
@Suppress("unused")
val replaceCapturePatch = bytecodePatch(
    name = "動画差し替え PoC",
    description = "固定パスに動画があるとき、録画完了時にその動画へ差し替えます。",
) {
    compatibleWith(Constants.SETLOG_COMPATIBILITY)
    extendWith("extensions/cutlog.mpe")

    execute {
        val resultClass = captureResultToStringFingerprint.match().classDef
        val resultType = resultClass.type

        val constructor = resultClass.methods.singleOrNull { it.name == "<init>" }
            ?: throw PatchException("Expected one CollectionCameraResult constructor.")
        if (constructor.parameterTypes.map { it.toString() } != RESULT_CONSTRUCTOR_PARAMETERS) {
            throw PatchException("Unexpected CollectionCameraResult constructor: ${constructor.parameterTypes}")
        }
        // コンストラクタは引数順に代入するので、代入順がそのまま引数と field の対応になる。
        val fields = constructor.implementation!!.instructions
            .filter { it.opcode in IPUT_OPCODES }
            .map { (it as ReferenceInstruction).reference as FieldReference }
        if (fields.map { it.type } != RESULT_CONSTRUCTOR_PARAMETERS) {
            throw PatchException("Unexpected CollectionCameraResult fields: $fields")
        }
        val (uri, mimeType) = fields
        val lensFacing = fields[5]
        val timestamp = fields[7]
        val utcOffset = fields[8]
        val countryCode = fields[9]

        fun ref(field: FieldReference) = "$resultType->${field.name}:${field.type}"

        // v0〜v11 を range 呼び出しに使う。v12 は差し替え結果、p0 は v13。
        val helper = MutableMethod(
            ImmutableMethod(
                resultType,
                HELPER_NAME,
                listOf(ImmutableMethodParameter(resultType, null, null)),
                resultType,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                MutableMethodImplementation(14),
            ),
        )
        helper.addInstructions(
            0,
            """
                iget-object v1, p0, ${ref(uri)}
                invoke-static {v1}, $INTERCEPTOR->onCaptured(Landroid/net/Uri;)$REPLACEMENT
                move-result-object v12
                if-nez v12, :replaced
                return-object p0
                :replaced
                iget-object v2, p0, ${ref(mimeType)}
                iget-wide v3, v12, $REPLACEMENT->durationMs:J
                iget v5, v12, $REPLACEMENT->width:I
                iget v6, v12, $REPLACEMENT->height:I
                iget v7, p0, ${ref(lensFacing)}
                iget-boolean v8, v12, $REPLACEMENT->hasAudio:Z
                iget-object v9, p0, ${ref(timestamp)}
                iget-object v10, p0, ${ref(utcOffset)}
                iget-object v11, p0, ${ref(countryCode)}
                new-instance v0, $resultType
                invoke-direct/range {v0 .. v11}, $resultType-><init>(${RESULT_CONSTRUCTOR_PARAMETERS.joinToString("")})V
                return-object v0
            """.trimIndent(),
        )
        resultClass.methods.add(helper)
        resultClass.directMethods.add(helper)

        val deliverResult = Fingerprint(
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
            returnType = "V",
            parameters = listOf(resultType),
            strings = listOf("cancelled", "result", "requestKey"),
        ).match().method

        // 二重呼び出しを弾く判定の後に差し込み、同じ録画を二度置き換えない。
        val guardIndex = deliverResult.implementation!!.instructions
            .indexOfFirst { it.opcode == Opcode.IPUT_BOOLEAN }
        if (guardIndex < 0) throw PatchException("Capture result guard not found.")
        deliverResult.addInstructions(
            guardIndex + 1,
            """
                invoke-static {p1}, $resultType->$HELPER_NAME($resultType)$resultType
                move-result-object p1
            """.trimIndent(),
        )
    }
}
