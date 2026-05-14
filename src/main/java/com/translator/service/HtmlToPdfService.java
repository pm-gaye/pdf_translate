package com.translator.service;

import java.io.ByteArrayOutputStream;

import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Service de conversion HTML → PDF via OpenHTMLtoPDF.
 *
 * OpenHTMLtoPDF utilise PDFBox en backend et supporte :
 *  - CSS2/CSS3
 *  - Polices personnalisées (TTF/OTF)
 *  - RTL / BiDi
 *  - SVG via Batik
 *  - Bookmarks, liens, métadonnées
 */
@ApplicationScoped
public class HtmlToPdfService {

    private static final Logger log = LoggerFactory.getLogger(HtmlToPdfService.class);

    /**
     * Convertit un HTML string en bytes PDF.
     *
     * @param html      HTML traduit (sortie de HtmlTranslationService)
     * @param baseUri   URI de base pour résoudre les ressources relatives (images, CSS)
     * @return          bytes du PDF généré
     */
    public byte[] convertHtmlToPdf(String html, String baseUri) throws Exception {
        log.info("Début conversion HTML→PDF. Taille HTML : {} caractères", html.length());

        // 1. Parser le HTML avec Jsoup
        Document jsoupDoc = Jsoup.parse(html);
        jsoupDoc.outputSettings().syntax(Document.OutputSettings.Syntax.xml);

        // S'assurer que le charset est UTF-8
        if (jsoupDoc.head().select("meta[charset]").isEmpty()) {
            jsoupDoc.head().prepend("<meta charset=\"UTF-8\"/>");
        }

        // Injecter un CSS minimal pour améliorer le rendu PDF
        jsoupDoc.head().append("<style>" + getPdfCss() + "</style>");
        jsoupDoc.select("style").forEach(s -> {
            if (s.html().contains("@font-face") && s.html().contains("base64")) {
                s.remove(); // retirer les fonts base64 si OpenHTMLtoPDF les rejette
            }
        });
        // 2. Convertir Jsoup Document en W3C DOM (requis par OpenHTMLtoPDF)
        W3CDom w3cDom = new W3CDom();
        org.w3c.dom.Document w3cDoc = w3cDom.fromJsoup(jsoupDoc);

        // 3. Générer le PDF avec OpenHTMLtoPDF
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();
        builder.withW3cDocument(w3cDoc, baseUri != null ? baseUri : "");
        builder.toStream(baos);

        // Métadonnées du PDF
        builder.withProducer("PDF Translator FR→EN");

        builder.run();

        byte[] pdfBytes = baos.toByteArray();
        log.info("Conversion HTML→PDF terminée. Taille PDF : {} octets", pdfBytes.length);
        return pdfBytes;
    }

    /**
     * Convertit un HTML string en PDF sans URI de base.
     */
    public byte[] convertHtmlToPdf(String html) throws Exception {
        return convertHtmlToPdf(html, null);
    }

    /**
     * CSS minimal injecté dans le HTML avant la conversion PDF.
     * Améliore la lisibilité et la mise en page du PDF généré.
     */
    private String getPdfCss() {
        return
            // Supprimer bordures bleues et outlines
            "* { border: none !important; outline: none !important; box-shadow: none !important; }" +
            // Ne pas toucher au positionnement absolu de PDF2DOM
            "body { margin: 0 !important; padding: 0 !important; }" +
            // Les divs de page gardent leurs dimensions exactes
            "div.page { position: relative !important; overflow: hidden; }" +
            // Les divs texte gardent leur position absolue
            "div.p { position: absolute !important; white-space: nowrap; }";
    }
}
