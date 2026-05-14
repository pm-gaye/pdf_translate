package com.translator.service;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.fit.pdfdom.PDFDomTree;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Service de conversion PDF → HTML via PDF2DOM.
 * Utilise la bibliothèque pdf2dom (CSSBox) pour transformer
 * un PDF en DOM HTML fidèle au mise en page originale.
 */
@ApplicationScoped
public class PdfToHtmlService {

    private static final Logger log = LoggerFactory.getLogger(PdfToHtmlService.class);

    /**
     * Convertit un PDF (en bytes) en HTML string.
     * Pipeline : PDFBox parse le PDF → PDF2DOM génère le DOM → Transformer sérialise en HTML.
     *
     * @param pdfBytes  bytes du PDF source
     * @param fileName  nom du fichier (pour logs)
     * @return HTML string représentant le contenu du PDF
     */
    public String convertPdfToHtml(byte[] pdfBytes, String fileName) throws Exception {
        log.info("Début conversion PDF→HTML pour : {}", fileName);

        try (PDDocument document = PDDocument.load(pdfBytes)) {
            int pageCount = document.getNumberOfPages();
            log.info("PDF chargé : {} pages", pageCount);

            // PDF2DOM : génère un org.w3c.dom.Document HTML
            PDFDomTree pdfDomTree = new PDFDomTree();
            StringWriter writer = new StringWriter();

            // Parsing du PDF vers DOM
            org.w3c.dom.Document htmlDom = pdfDomTree.createDOM(document);

            // Sérialisation du DOM en String HTML
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.METHOD, "html");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");

            DOMSource source = new DOMSource(htmlDom);
            StreamResult result = new StreamResult(writer);
            transformer.transform(source, result);

            String html = writer.toString();
            log.info("Conversion PDF→HTML terminée. Taille HTML : {} caractères", html.length());

            // Post-traitement Jsoup : nettoyer et normaliser
            html = postProcessHtml(html);

            return html;
        }
    }

    /**
     * Post-traitement du HTML généré :
     * - Suppression des styles de positionnement absolus qui gênent la recomposition
     * - Normalisation des espaces dans les textes
     * - Ajout du charset UTF-8
     */
    private String postProcessHtml(String rawHtml) {
        Document doc = Jsoup.parse(rawHtml);
        doc.charset(StandardCharsets.UTF_8);

        if (doc.head().select("meta[charset]").isEmpty()) {
            doc.head().prepend("<meta charset=\"UTF-8\">");
        }

        // Supprimer les positions absolues qui cassent le rendu OpenHTMLtoPDF
        for (Element el : doc.select("[style]")) {
            String style = el.attr("style");
            // Retirer left/top/position:absolute mais garder font-size, font-family, etc.
            style = style.replaceAll("position\\s*:\\s*absolute\\s*;?", "");
            style = style.replaceAll("left\\s*:\\s*[\\d.]+px\\s*;?", "");
            style = style.replaceAll("top\\s*:\\s*[\\d.]+px\\s*;?", "");
            el.attr("style", style.trim());
        }

        // Nettoyer les éléments vides
        for (Element el : doc.select("div, span, p")) {
            if (el.text().trim().isEmpty() && el.children().isEmpty()) {
                el.remove();
            }
        }

        return doc.outerHtml();
    }

    /**
     * Compte le nombre de pages d'un PDF.
     */
    public int getPageCount(byte[] pdfBytes) throws IOException {
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            return document.getNumberOfPages();
        }
    }

    /**
     * Extrait le texte brut du PDF pour compter les mots.
     */
    public int getWordCount(byte[] pdfBytes) throws IOException {
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            String text = stripper.getText(document);
            if (text == null || text.trim().isEmpty()) return 0;
            return text.trim().split("\\s+").length;
        }
    }
}
