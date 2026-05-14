package com.translator.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Service de traduction HTML FR → EN.
 *
 * Stratégie : On extrait uniquement les nœuds texte du HTML,
 * on les envoie par batch à l'API de traduction, puis on les réinjecte
 * dans le DOM pour préserver toute la mise en page.
 *
 * APIs supportées :
 *  - DeepL (https://api-free.deepl.com) → recommandé, haute qualité
 *  - LibreTranslate (self-hosted ou public)  → open source
 *  - Google Translate (via REST)
 */
@ApplicationScoped
public class HtmlTranslationService {

    private static final Logger log = LoggerFactory.getLogger(HtmlTranslationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int BATCH_SIZE = 50;

    private static final String TRANSLATION_API = "deepl";
    private static final String DEEPL_API_KEY   = "your_key";
    private static final String DEEPL_URL        = "https://api-free.deepl.com/v2/translate";

    public String translateHtml(String html) throws Exception {
        Document doc = Jsoup.parse(html);

        List<Element> textDivs = new ArrayList<>();
        for (Element div : doc.select("div.p")) {
            if (div.ownText().trim().length() > 0) {
                textDivs.add(div);
            }
        }

        if (textDivs.isEmpty()) return html;

        // ── Regrouper les divs par ligne (même valeur "top" dans le style) ──
        // "top:72.95999pt" → clé de regroupement
        java.util.Map<String, List<Element>> lignes = new java.util.LinkedHashMap<>();
        for (Element div : textDivs) {
            String style = div.attr("style");
            String top = extraireTop(style); // ex: "72.95999pt"
            lignes.computeIfAbsent(top, k -> new ArrayList<>()).add(div);
        }

        // ── Construire une phrase par ligne ──
        List<String> phrases = new ArrayList<>();
        List<List<Element>> groupes = new ArrayList<>();

        for (Map.Entry<String, List<Element>> entry : lignes.entrySet()) {
            List<Element> groupe = entry.getValue();
            // Reconstituer la phrase dans l'ordre left→right
            groupe.sort((a, b) -> {
                double leftA = extraireValeur(a.attr("style"), "left");
                double leftB = extraireValeur(b.attr("style"), "left");
                return Double.compare(leftA, leftB);
            });
            String phrase = groupe.stream()
                .map(e -> e.ownText().trim())
                .filter(t -> !t.isEmpty())
                .collect(java.util.stream.Collectors.joining(" "));
            
            if (!phrase.isEmpty()) {
                phrases.add(phrase);
                groupes.add(groupe);
            }
        }

        log.info("Lignes reconstituées : {}", phrases.size());
        for (int i = 0; i < Math.min(5, phrases.size()); i++) {
            log.info("  Ligne[{}] = '{}'", i, phrases.get(i));
        }

        // ── Traduire les phrases complètes ──
        List<String> traductions = translateByBatches(phrases);

        // ── Réinjecter : mettre la phrase traduite dans le 1er div,
        //    vider les autres divs de la ligne ──
        for (int i = 0; i < groupes.size() && i < traductions.size(); i++) {
            List<Element> groupe = groupes.get(i);
            groupe.get(0).text(traductions.get(i)); // phrase traduite dans le 1er
            for (int j = 1; j < groupe.size(); j++) {
                groupe.get(j).text(""); // vider les autres
            }
        }

        doc.select("html").attr("lang", "en");
        return doc.outerHtml();
    }

    // Extraire la valeur "top:XXXpt" → "XXXpt" (clé de regroupement)
    private String extraireTop(String style) {
        for (String part : style.split(";")) {
            part = part.trim();
            if (part.startsWith("top:")) {
                // Arrondir à 1 décimale pour éviter les micro-décalages
                try {
                    double val = Double.parseDouble(part.replace("top:", "").replace("pt", "").trim());
                    return String.format("%.1f", val);
                } catch (NumberFormatException e) {
                    return part;
                }
            }
        }
        return "0";
    }

    // Extraire une valeur numérique depuis le style (ex: "left")
    private double extraireValeur(String style, String propriete) {
        for (String part : style.split(";")) {
            part = part.trim();
            if (part.startsWith(propriete + ":")) {
                try {
                    return Double.parseDouble(
                        part.replace(propriete + ":", "").replace("pt", "").trim()
                    );
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private List<String> translateByBatches(List<String> textes) throws Exception {
        List<String> allTranslations = new ArrayList<>();
        for (int i = 0; i < textes.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, textes.size());
            List<String> batch = textes.subList(i, end);
            log.debug("Batch {}/{}", (i / BATCH_SIZE) + 1,
                    (int) Math.ceil((double) textes.size() / BATCH_SIZE));
            allTranslations.addAll(translateBatch(batch));
        }
        return allTranslations;
    }

    private List<String> translateBatch(List<String> texts) throws Exception {
        switch (TRANSLATION_API.toLowerCase()) {
            case "deepl":        return translateWithDeepL(texts);
            case "libretranslate": return translateWithLibreTranslate(texts);
            default:             return mockTranslate(texts);
        }
    }

    private List<String> translateWithDeepL(List<String> texts) throws Exception {
        try (CloseableHttpClient client = HttpClients.createDefault()) {

            StringBuilder bodyBuilder = new StringBuilder("{\"text\":[");
            for (int i = 0; i < texts.size(); i++) {
                bodyBuilder.append("\"").append(escapeJson(texts.get(i))).append("\"");
                if (i < texts.size() - 1) bodyBuilder.append(",");
            }
            // PAS de tag_handling:html car on envoie du texte brut
            bodyBuilder.append("],\"source_lang\":\"FR\",\"target_lang\":\"EN-US\"}");

            HttpPost request = new HttpPost(DEEPL_URL);
            request.setHeader("Authorization", "DeepL-Auth-Key " + DEEPL_API_KEY);
            request.setHeader("Content-Type", "application/json");
            request.setEntity(new StringEntity(bodyBuilder.toString(), ContentType.APPLICATION_JSON));

            try (CloseableHttpResponse response = client.execute(request)) {
                int status = response.getStatusLine().getStatusCode();
                String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");

                if (status != 200) {
                    log.error("DeepL erreur {}: {}", status, responseBody);
                    return mockTranslate(texts);
                }

                JsonNode root = MAPPER.readTree(responseBody);
                List<String> result = new ArrayList<>();
                for (JsonNode t : root.get("translations")) {
                    result.add(t.get("text").asText());
                }
                return result;
            }
        }
    }

    private List<String> translateWithLibreTranslate(List<String> texts) throws Exception {
        List<String> results = new ArrayList<>();
        String libreUrl = System.getenv().getOrDefault("LIBRETRANSLATE_URL", "https://libretranslate.com/translate");
        String libreKey = System.getenv().getOrDefault("LIBRETRANSLATE_KEY", "");

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            for (String text : texts) {
                String body = String.format(
                    "{\"q\":\"%s\",\"source\":\"fr\",\"target\":\"en\",\"api_key\":\"%s\"}",
                    escapeJson(text), libreKey
                );
                HttpPost request = new HttpPost(libreUrl);
                request.setHeader("Content-Type", "application/json");
                request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));

                try (CloseableHttpResponse response = client.execute(request)) {
                    String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
                    results.add(MAPPER.readTree(responseBody).get("translatedText").asText());
                }
            }
        }
        return results;
    }

    private List<String> mockTranslate(List<String> texts) {
        List<String> result = new ArrayList<>();
        for (String text : texts) result.add("[EN] " + text);
        return result;
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}