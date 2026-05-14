package com.translator.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.Serializable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.translator.model.TranslationResult;
import com.translator.model.TranslationStatus;

/**
 * Service orchestrateur du pipeline de traduction PDF.
 *
 * Pipeline complet :
 *   PDF bytes
 *     ↓  [PdfToHtmlService]
 *   HTML intermédiaire
 *     ↓  [HtmlTranslationService]
 *   HTML traduit
 *     ↓  [HtmlToPdfService]
 *   PDF traduit (bytes)
 */
@ApplicationScoped
public class TranslationOrchestrator implements Serializable{

    /**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	private static final Logger log = LoggerFactory.getLogger(TranslationOrchestrator.class);

    @Inject
    private PdfToHtmlService pdfToHtmlService;

    @Inject
    private HtmlTranslationService htmlTranslationService;

    @Inject
    private HtmlToPdfService htmlToPdfService;

    /**
     * Exécute le pipeline complet de traduction.
     *
     * @param pdfBytes   bytes du PDF à traduire
     * @param fileName   nom du fichier source
     * @param callback   listener de progression (peut être null)
     * @return           TranslationResult avec le PDF traduit
     */
    public TranslationResult translate(byte[] pdfBytes, String fileName,
                                        ProgressCallback callback) throws Exception {

        long startTime = System.currentTimeMillis();
        TranslationResult result = new TranslationResult();
        result.setOriginalFileName(fileName);

        try {
            // ── Étape 1 : Récupérer les méta-données ──────────────────────
            notify(callback, TranslationStatus.UPLOADING, "Analyse du PDF...", 10);
            int pageCount = pdfToHtmlService.getPageCount(pdfBytes);
            int wordCount = pdfToHtmlService.getWordCount(pdfBytes);
            result.setPageCount(pageCount);
            result.setWordCount(wordCount);
            log.info("PDF analysé : {} pages, {} mots", pageCount, wordCount);

            // ── Étape 2 : PDF → HTML (PDF2DOM) ────────────────────────────
            notify(callback, TranslationStatus.CONVERTING_TO_HTML, "Conversion PDF → HTML...", 25);
            String intermediateHtml = pdfToHtmlService.convertPdfToHtml(pdfBytes, fileName);
            result.setIntermediateHtml(intermediateHtml);
            log.info("PDF→HTML terminé");

            // ── Étape 3 : Traduction HTML FR → EN ─────────────────────────
            notify(callback, TranslationStatus.TRANSLATING, "Traduction en cours...", 55);
            String translatedHtml = htmlTranslationService.translateHtml(intermediateHtml);
            result.setTranslatedHtml(translatedHtml);
            log.info("Traduction HTML terminée");

            // ── Étape 4 : HTML traduit → PDF (OpenHTMLtoPDF) ──────────────
            notify(callback, TranslationStatus.CONVERTING_TO_PDF, "Génération du PDF traduit...", 80);
            byte[] translatedPdfBytes = htmlToPdfService.convertHtmlToPdf(translatedHtml);
            result.setTranslatedPdfBytes(translatedPdfBytes);

            // Nom du fichier de sortie
            String baseName = fileName.replaceAll("(?i)\\.pdf$", "");
            result.setTranslatedFileName(baseName + "_EN.pdf");

            // Temps de traitement
            result.setProcessingTimeMs(System.currentTimeMillis() - startTime);

            notify(callback, TranslationStatus.DONE, "Traduction terminée !", 100);
            log.info("Pipeline terminé en {}ms", result.getProcessingTimeMs());

        } catch (Exception e) {
            notify(callback, TranslationStatus.ERROR, "Erreur : " + e.getMessage(), -1);
            log.error("Erreur dans le pipeline de traduction", e);
            throw e;
        }

        return result;
    }

    private void notify(ProgressCallback callback, TranslationStatus status,
                        String message, int progress) {
        if (callback != null) {
            callback.onProgress(status, message, progress);
        }
    }

    /**
     * Interface callback pour la progression du pipeline.
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(TranslationStatus status, String message, int progressPercent);
    }
}
