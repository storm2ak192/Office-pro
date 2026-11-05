package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val TAG = "AiAssistantVM"

sealed interface AiUiState {
    data object Idle : AiUiState
    data object Loading : AiUiState
    data class Streaming(val text: String) : AiUiState
    data class Success(val text: String) : AiUiState
    data class Error(val message: String) : AiUiState
}

enum class AiTaskPreset(val titleAr: String, val titleEn: String, val systemHint: String) {
    WRITE_ARTICLE("كتابة نص أو مقال", "Write Article", "اكتب مقالاً أو نصاً احترافياً ومنظماً بالعناوين والفقرات حول الموضوع التالي:"),
    REWRITE("إعادة صياغة النص", "Rewrite Text", "أعد صياغة النص التالي بأسلوب أكثر احترافية ووضوحاً مع الحفاظ على المعنى:"),
    PROOFREAD("تصحيح الأخطاء اللغوية", "Proofread & Fix", "صحح الأخطاء الإملائية والنحوية والأسلوبية في النص التالي مع توضيح التعديلات باختصار:"),
    SUMMARIZE("تلخيص مستند أو نص", "Summarize", "لخّص المحتوى التالي في نقاط مركزة وواضحة:"),
    TRANSLATE("ترجمة (عربي <-> إنجليزي)", "Translate AR/EN", "ترجم النص التالي بدقة واحترافية (إذا كان بالعربية ترجمه للإنجليزية، وإذا كان بالإنجليزية ترجمه للعربية):"),
    REPORT("إنشاء تقرير عمل", "Generate Report", "أنشئ تقرير عمل متكاملاً يتضمن الملخص التنفيذي، التفاصيل، والتوصيات بناءً على المعطيات التالية:"),
    LETTER_CV("إنشاء خطاب أو سيرة ذاتية", "Letter / CV", "أنشئ خطاباً رسمياً أو سيرة ذاتية احترافية منظمة بناءً على المعلومات التالية:"),
    EXCEL_FORMULA("إنشاء أو شرح معادلة Excel", "Excel Formula", "اكتب معادلة Excel الدقيقة واشرح طريقة عملها خطوة بخطوة للمطلوب التالي:"),
    ANALYZE_DATA("تحليل بيانات وجداول", "Analyze Data", "حلل البيانات التالية واستخرج أبرز المؤشرات والاتجاهات والتوصيات:"),
    PPT_OUTLINE("إعداد مخطط عرض تقديمي", "PPT Outline", "أنشئ مخططاً تفصيلياً لشرائح عرض تقديمي (عنوان كل شريحة ونقاطها الرئيسية) حول:")
}

class AiAssistantViewModel : ViewModel() {

    private val model by lazy {
        Firebase.ai.generativeModel("gemini-3.8-flash")
    }

    private val _uiState = MutableStateFlow<AiUiState>(AiUiState.Idle)
    val uiState: StateFlow<AiUiState> = _uiState.asStateFlow()

    fun runPrompt(preset: AiTaskPreset?, userInput: String, attachedContext: String = "") {
        if (userInput.isBlank() && attachedContext.isBlank()) {
            _uiState.value = AiUiState.Error("يرجى إدخال النص أو السؤال أولاً.")
            return
        }

        val fullPrompt = buildString {
            if (preset != null) {
                append(preset.systemHint).append("\n\n")
            }
            if (userInput.isNotBlank()) {
                append(userInput).append("\n\n")
            }
            if (attachedContext.isNotBlank()) {
                append("--- محتوى المستند المرفق ---\n")
                append(attachedContext.take(12000))
            }
        }

        viewModelScope.launch {
            _uiState.value = AiUiState.Loading
            val accumulated = StringBuilder()
            try {
                withTimeout(60_000L) {
                    model.generateContentStream(fullPrompt).collect { chunk ->
                        val part = chunk.text.orEmpty()
                        accumulated.append(part)
                        _uiState.value = AiUiState.Streaming(accumulated.toString())
                    }
                }
                if (accumulated.isNotBlank()) {
                    _uiState.value = AiUiState.Success(accumulated.toString())
                } else {
                    _uiState.value = AiUiState.Error("لم يُرجع النموذج أي استجابة.")
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "AI generation timed out", e)
                _uiState.value = AiUiState.Error("انتهت مهلة الاتصال (60 ثانية). يرجى المحاولة مرة أخرى.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Firebase AI generation failed", e)
                _uiState.value = AiUiState.Error(
                    e.localizedMessage ?: "تعذر الاتصال بمساعد الذكاء الاصطناعي. تأكد من توفر الإنترنت."
                )
            }
        }
    }

    fun reset() {
        _uiState.value = AiUiState.Idle
    }
}
