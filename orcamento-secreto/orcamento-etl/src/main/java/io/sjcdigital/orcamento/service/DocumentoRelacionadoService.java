package io.sjcdigital.orcamento.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.inject.Named;
import javax.transaction.Transactional;

import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.sjcdigital.orcamento.model.entity.Documentos;
import io.sjcdigital.orcamento.model.entity.Emendas;
import io.sjcdigital.orcamento.model.pojo.DocumentosDataPojo;
import io.sjcdigital.orcamento.model.pojo.DocumentosRelacionadosPojo;
import io.sjcdigital.orcamento.model.repository.DocumentoRepository;
import io.sjcdigital.orcamento.model.repository.EmendasRepository;
import io.sjcdigital.orcamento.resource.client.DocumentosRelacionadosClient;
import io.sjcdigital.orcamento.utils.Constantes;
import io.sjcdigital.orcamento.utils.FileUtil;
import io.sjcdigital.orcamento.utils.PortalTransparenciaConstantes;

/**
 * @author Pedro Hos <pedro-hos@outlook.com>
 *
 */
@ApplicationScoped
@Named("documentosRelacionadosBean")
@RegisterForReflection
public class DocumentoRelacionadoService extends Service {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentoRelacionadoService.class);
    
    @Inject DetalhesDocumentoService detalhesDoc;
    @Inject EmendasRepository emendaReporitory;
    @Inject DocumentoRepository documentoRepository;
    
    public static void separaDocumentos() {
        
        DocumentoRelacionadoService d = new DocumentoRelacionadoService();
        
        try (Stream<Path> walk = Files.walk(Paths.get(Constantes.DOC_RELACIONADOS_PATH))) {
            
            walk.filter(Files::isRegularFile).collect(Collectors.toList()).forEach(f -> {
                DocumentosRelacionadosPojo pojo = d.readJsonFile(Constantes.DOC_RELACIONADOS_PATH, f.getFileName().toString());
                
                Collection<List<DocumentosDataPojo>> partition = d.partition(pojo.getData(), 5000);
                int i = 1;
                
                for (List<DocumentosDataPojo> p : partition) {
                    
                    DocumentosRelacionadosPojo drp = new DocumentosRelacionadosPojo();
                    drp.setDraw(pojo.getDraw());
                    drp.setIdEmenda(pojo.getIdEmenda());
                    drp.setRecordsFiltered(pojo.getRecordsFiltered());
                    drp.setRecordsTotal(pojo.getRecordsTotal());
                    drp.setData(p);
                    
                    FileUtil.salvaJSON(drp, Constantes.DOC_RELACIONADOS_PATH + "partition/" + pojo.getIdEmenda() + "/", i + ".json");
                    i++;
                }
                
            });;
            
            
        } catch (IOException e) {
            e.printStackTrace();
        }
        
    }
    
    @Transactional
    public void processaMuitosDocumentosArquivo(Integer index) {
        populaDocumentosRelacionadosArquivo(emendaReporitory.buscaArquivosGrandesNaoProcesados().get(0), index);
    }
    
    @Transactional
    public void populaDocumentosRelacionadosArquivo(Emendas emenda, Integer index) {
        
        String filePath = Constantes.DOC_RELACIONADOS_PATH + "partition/" + emenda.id + "/";
        List<Path> files = listFiles(Paths.get(filePath));
            
            LOGGER.info("[INICIADO] Processo para emenda id " + emenda.id + " [ " + index + " ]");
            
            DocumentosRelacionadosPojo pojo = this.readJsonFile(filePath, ""+index);
            Collection<Documentos> documentos = converteParaDocumentos(pojo, emenda);
            emenda.documentos.addAll(documentos);
            
            if (index == files.size()) {
                emenda.processado = Boolean.TRUE;
                emenda.erro = Boolean.FALSE;
                emenda.processando = Boolean.FALSE;
            }
            emendaReporitory.persist(emenda);
            
            LOGGER.info("[FINALIZADO] Processo para emenda id " + emenda.id + " [ " + index + " ]");
        
        LOGGER.info("[FINALIZADO TODO PROCESSO]");
        
    }
    
    public static List<Path> listFiles(Path path) {

        List<Path> result;
        try (Stream<Path> walk = Files.walk(path)) {
            result = walk.filter(Files::isRegularFile).collect(Collectors.toList());
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        } 
        
        return result;

    }
    
    public Collection<List<DocumentosDataPojo>> partition (List<DocumentosDataPojo> list, int chunkSize) {
        final AtomicInteger counter = new AtomicInteger();
        return list.stream()
                .collect(Collectors.groupingBy(it -> counter.getAndIncrement() / chunkSize))
                .values();
    }
    
    /**
     * @param pojo
     * @return
     */
    @Transactional
    public Collection<Documentos> converteParaDocumentos(DocumentosRelacionadosPojo pojo, Emendas emenda) {
        
        LOGGER.info("[INICIANDO] Busca de Documentos para " + emenda.codigoEmenda + " com " + emenda.quantidadeDocumentos + " documentos.");
        
        List<String> codigos = pojo.getData().stream().map(DocumentosDataPojo::getCodigoDocumento).collect(Collectors.toList());
        Map<String, Documentos> documentos = documentoRepository.findByCodigoDocumento(codigos);
        
        pojo.getData().forEach(p -> {
            
            if(!documentos.containsKey(p.getCodigoDocumento())) {
                Documentos documento = Documentos.fromDocumentoDataPojo(p);
                documento.getEmenda().add(emenda);
                String key = p.getFase() + "-" + p.getCodigoDocumento();
                documentos.put(key, documento);
            }
            
        });
        
        
        documentoRepository.persist(documentos.values());
        
        LOGGER.info("[FINALIZADO] Busca de Documentos para " + emenda.codigoEmenda + " com " + emenda.quantidadeDocumentos + " documentos.");
        
        return documentos.values();
    }

    private DocumentosRelacionadosPojo readJsonFile(String filePath, String filename) {
        
        try {
            filename = filename.contains(".json") ? filename : filename + ".json";
            Path path = Paths.get(filePath + filename);
            String content = Files.readString(path);
            ObjectMapper mapper = new ObjectMapper();
            
            return mapper.readValue(content, DocumentosRelacionadosPojo.class);
            
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
        
    }
    
    public void buscaDocumentosRelacionado(List<Emendas> emendas) {
        ExecutorService executor = Executors.newFixedThreadPool(30);
        emendas.forEach(emenda -> 
            executor.submit(() -> buscaDocumentosRelacionado(emenda))
        );
    }
    
    @Transactional
    public void salvaMuitosDocumentosRelacionado(List<Emendas> emendas) {
        emendas.forEach(e ->  { 
            Emendas emenda = emendaReporitory.findById(e.id);
            salvaMuitosDocumentosRelacionado(emenda);
        } );
    }
    
    /**
     * @param id
     * @param codigoEmenda
     */
    @Transactional
    public void salvaMuitosDocumentosRelacionado(Emendas emenda) {
        
        try {

            LOGGER.info("[INICIANDO] Busca de Documentos para " + emenda.codigoEmenda + " com " + emenda.quantidadeDocumentos + " documentos.");

            int offset = 0;
            int qnde = recuperaQuantidadeDeDocumentos(emenda.codigoEmenda);
            
            emenda.processando = Boolean.TRUE;
            emendaReporitory.persist(emenda);
            
            DocumentosRelacionadosPojo docRelacionado = buscaDocumentos(offset, qnde, emenda.codigoEmenda);
            docRelacionado.setIdEmenda(emenda.id);
            FileUtil.salvaJSON(docRelacionado, Constantes.DOC_RELACIONADOS_PATH, emenda.id + "-" + emenda.codigoEmenda);
            
            LOGGER.info("[FINALIZADO] Busca de Documentos para " + emenda.codigoEmenda + " com " + emenda.quantidadeDocumentos + " documentos.");

        } catch (Exception e) {
            emenda.erro = Boolean.TRUE;
            emenda.processando = Boolean.FALSE;
            emendaReporitory.persist(emenda);
            LOGGER.error(e.getMessage());
        }
    }
    
    
    @Transactional
    public void processaMuitosDocumentosRelacionados(DocumentosRelacionadosPojo pojo, Emendas emenda){
        
        try {
        LOGGER.info("[PROCESSANDO] Documentos Relacionados - " + emenda.codigoEmenda);
        
        
        ExecutorService newSingleThreadExecutor = Executors.newFixedThreadPool(100);
        
        pojo.getData().forEach(data -> {
            
            newSingleThreadExecutor.submit(() -> {
                
                LOGGER.info("[INICIO] Salvando docs");
                
                Documentos documentos = documentoRepository.findByFaseAndCodigoDocumento(data.getFase(), data.getCodigoDocumento());
                
                if(Objects.isNull(documentos)) {
                    documentos = Documentos.fromDocumentoDataPojo(data);
                    documentos.getEmenda().add(emenda);
                }
                
                documentoRepository.persist(documentos);
                emenda.documentos.add(documentos);
                emendaReporitory.persistAndFlush(emenda);
            });
            
            emenda.processado = Boolean.TRUE;
            emenda.erro = Boolean.FALSE;
            emenda.processando = Boolean.FALSE;
            LOGGER.info("[FINALIZADO] Documentos salvos.");
            
        });
        
        } catch (Exception e) {
            
            emenda.processado = Boolean.FALSE;
            emenda.erro = Boolean.TRUE;
            emenda.processando = Boolean.FALSE;
            emendaReporitory.persistAndFlush(emenda);
            
           e.printStackTrace();
        }
        
    }
    
    /**
     * @param id
     * @param codigoEmenda
     */
    @Transactional
    public void buscaDocumentosRelacionado(Emendas emendas) {
        
        Emendas emenda = emendaReporitory.findById(emendas.id);
        
        try {
            
            LOGGER.info("[INICIANDO] Busca de Documentos para " + emenda.codigoEmenda);
            
            int offset = 0;
            int quantidadeDocumentos = recuperaQuantidadeDeDocumentos(emendas.codigoEmenda);
            
            emenda.quantidadeDocumentos = quantidadeDocumentos;
            
            if(quantidadeDocumentos > 20000) {
                
                emenda.muitosDocumentos = true;
                emendaReporitory.persist(emenda);
                
            } else {
                
                emenda.processando = Boolean.TRUE;
                emendaReporitory.persist(emenda);
                
                int maxAttempt = Integer.MAX_VALUE;
                
                DocumentosRelacionadosPojo docRelacionado = null;
                
                while(offset < maxAttempt) {
                    
                    if(Objects.isNull(docRelacionado)) {
                        docRelacionado = buscaDocumentos(offset, emenda.codigoEmenda);
                        maxAttempt = Integer.valueOf(docRelacionado.getRecordsTotal());
                    } else {
                        docRelacionado.getData().addAll(buscaDocumentos(offset, emenda.codigoEmenda).getData());
                    }
                    
                    offset = offset +  DocumentosRelacionadosClient.TAMANHO_PAGINA;
                }
                
                LOGGER.info("Quantidade dispon??vel x recuperada " + docRelacionado.getRecordsTotal() + " x " + docRelacionado.getData().size());
                processaDocumentosRelacionados(docRelacionado, emenda);
            }
            
        } catch (Exception e) {
            emenda.erro = Boolean.TRUE;
            emenda.processando = Boolean.FALSE;
            emendaReporitory.persist(emenda);
            LOGGER.error(e.getMessage());
        }
    }
    
    /**
     * @return
     */
    protected int recuperaQuantidadeDeDocumentos(final String codigoEmenda) {
        
        try {
            
            String recordsTotal = buscaDocumentos(0, 1, codigoEmenda).getRecordsTotal();
            LOGGER.info(recordsTotal + " Documentos para Emenda");
            return Integer.valueOf(recordsTotal);
            
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
        }
        
        return DocumentosRelacionadosClient.TAMANHO_PAGINA;
    }
    
    @Transactional
    public void processaDocumentosRelacionados(DocumentosRelacionadosPojo pojo, Emendas emenda) throws Exception {
        LOGGER.info("[PROCESSANDO] Documentos Relacionados - " + emenda.codigoEmenda);
        
        List<Documentos> docs = new ArrayList<>();
        
        pojo.getData().forEach(data -> {
            
            Documentos documentos = documentoRepository.findByFaseAndCodigoDocumento(data.getFase(), data.getCodigoDocumento());
            
            if(Objects.isNull(documentos)) {
                documentos = Documentos.fromDocumentoDataPojo(data);
                documentos.getEmenda().add(emenda);
            }
            
            docs.add(documentos);
            documentoRepository.persist(documentos);
            
        });
        
        emenda.documentos.addAll(docs);
        emenda.processado = Boolean.TRUE;
        emenda.erro = Boolean.FALSE;
        emenda.processando = Boolean.FALSE;
        emendaReporitory.persist(emenda);
        LOGGER.info("[FINALIZADO] " + docs.size() + " documentos salvos.");
    }
    
    public DocumentosRelacionadosPojo buscaDocumentos(final int offset, final int tamanhoPagina, final String codigoEmenda) {
        ResteasyWebTarget target = getTarget(PortalTransparenciaConstantes.URL);
        DocumentosRelacionadosClient proxy = target.proxy(DocumentosRelacionadosClient.class);
        DocumentosRelacionadosPojo pojo = proxy.pegaDocs( offset, codigoEmenda, 
                                                          DocumentosRelacionadosClient.PAGINACAO_SIMPLES, 
                                                          tamanhoPagina, 
                                                          DocumentosRelacionadosClient.DIRECAO_ORDENACAO, 
                                                          DocumentosRelacionadosClient.COLUNA_ORDENACAO );
        target.getResteasyClient().close();
        return pojo;
    }
    
    public DocumentosRelacionadosPojo buscaDocumentos(final int offset, final String codigoEmenda) {
        ResteasyWebTarget target = getTarget(PortalTransparenciaConstantes.URL);
        DocumentosRelacionadosClient proxy = target.proxy(DocumentosRelacionadosClient.class);
        DocumentosRelacionadosPojo pojo = proxy.pegaDocs( offset, codigoEmenda, 
                                                          DocumentosRelacionadosClient.PAGINACAO_SIMPLES, 
                                                          DocumentosRelacionadosClient.TAMANHO_PAGINA, 
                                                          DocumentosRelacionadosClient.DIRECAO_ORDENACAO, 
                                                          DocumentosRelacionadosClient.COLUNA_ORDENACAO );
        target.getResteasyClient().close();
        return pojo;
    }
    
    
    

}
