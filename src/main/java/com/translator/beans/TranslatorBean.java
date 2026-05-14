package com.translator.beans;

import java.io.ByteArrayInputStream;
import java.io.Serializable;

import org.primefaces.model.DefaultStreamedContent;
import org.primefaces.model.StreamedContent;
import org.primefaces.model.file.UploadedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import com.translator.dao.GenericDAO;
import com.translator.dao.GenericDAOImpl;
import com.translator.metier.Fichier;
import com.translator.model.TranslationResult;
import com.translator.service.TranslationOrchestrator;
import com.translator.utils.SpringBeansContext;

import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

@ViewScoped
@Named("translatorBean")
public class TranslatorBean implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(TranslatorBean.class);

    @Inject
    private TranslationOrchestrator orchestrator;

    private GenericDAO<Fichier> dao;
    
    private UserInfo userInfo;
    
    // ─── Fichier ───────────────────────────────────────────────────────
    private UploadedFile uploadedFile;

    // ─── État ─────────────────────────────────────────────────────────
    private boolean processing = false;
    private boolean done       = false;
    private boolean error      = false;
    private String  errorMessage;
    
    private TranslationResult result;

    @SuppressWarnings("unchecked")
	@PostConstruct
    public void init() {
        log.info("TranslatorBean initialisé");
        dao = new GenericDAOImpl<>(Fichier.class);
        userInfo =  SpringBeansContext.getContext().getBean(UserInfo.class);
        System.out.println(userInfo.getCurrentUser().getLogin());
        
    }

    // ─────────────────────────────────────────────────────────────────
    //  Action principale : tout se passe ici, de façon synchrone
    // ─────────────────────────────────────────────────────────────────

    public void startTranslation() {

        // Validation
        if (uploadedFile == null
                || uploadedFile.getContent() == null
                || uploadedFile.getContent().length == 0) {
            addError("Veuillez sélectionner un fichier PDF.");
            return;
        }
        if (!uploadedFile.getFileName().toLowerCase().endsWith(".pdf")) {
            addError("Seuls les fichiers PDF sont acceptés.");
            return;
        }

        processing = true;
        done       = false;
        error      = false;
        errorMessage = null;
        result     = null;

        try {
            result = orchestrator.translate(
                uploadedFile.getContent(),
                uploadedFile.getFileName(),
                null   // plus de callback de progression
            );
            done = true;
            
            Fichier f = new Fichier();
            System.out.println(userInfo.getCurrentUser().getLogin());
            f.setNomFichier(uploadedFile.getFileName());
            f.setUtilisateur(userInfo.getCurrentUser());
            
            dao.persist(f);
            System.out.println("Nouvelle ligne enregistre dans fichier");
            

        } catch (Exception e) {
            error        = true;
            errorMessage = e.getMessage();
            log.error("Erreur pipeline", e);

        } finally {
            processing = false;
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Téléchargement
    // ─────────────────────────────────────────────────────────────────

    public StreamedContent getDownloadFile() {
        if (result == null || result.getTranslatedPdfBytes() == null) {
            return DefaultStreamedContent.builder().build();
        }
        byte[] bytes = result.getTranslatedPdfBytes();
        return DefaultStreamedContent.builder()
            .name(result.getTranslatedFileName())
            .contentType("application/pdf")
            .stream(() -> new ByteArrayInputStream(bytes))
            .build();
    }

    // ─────────────────────────────────────────────────────────────────
    //  Reset
    // ─────────────────────────────────────────────────────────────────

    public void reset() {
        uploadedFile = null;
        processing   = false;
        done         = false;
        error        = false;
        errorMessage = null;
        result       = null;
        log.info("Bean réinitialisé");
    }

    // ─────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────

    public String getFormattedProcessingTime() {
        if (result == null) return "";
        long ms = result.getProcessingTimeMs();
        return ms < 1000 ? ms + " ms" : String.format("%.1f s", ms / 1000.0);
    }

    private void addError(String msg) {
        FacesContext.getCurrentInstance()
            .addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, msg, null));
    }

    // ─────────────────────────────────────────────────────────────────
    //  Getters / Setters
    // ─────────────────────────────────────────────────────────────────

    public UploadedFile getUploadedFile()     { return uploadedFile; }
    public void setUploadedFile(UploadedFile f) { this.uploadedFile = f; }

    public boolean isProcessing()  { return processing; }
    public boolean isDone()        { return done; }
    public boolean isError()       { return error; }
    public String  getErrorMessage() { return errorMessage; }
    public TranslationResult getResult() { return result; }
    
    public static ApplicationContext getContext() {
		ApplicationContext context= new ClassPathXmlApplicationContext ("SpringBeans.xml");
		return context;
	}
}