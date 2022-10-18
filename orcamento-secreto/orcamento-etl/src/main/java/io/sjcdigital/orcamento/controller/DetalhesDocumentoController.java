package io.sjcdigital.orcamento.controller;

import java.io.IOException;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

import io.sjcdigital.orcamento.model.entity.Documentos;
import io.sjcdigital.orcamento.model.entity.Favorecido;
import io.sjcdigital.orcamento.model.entity.OrgaoPagador;

/**
 * @author Pedro Hos <pedro-hos@outlook.com>
 *
 */
public class DetalhesDocumentoController {
    
    public static final String AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_9_2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/33.0.1750.152 Safari/537.36";
    
    /**
     * @param detalheURL
     * @param documento
     */
    public void preencheDetalhes(String detalheURL, Documentos documento) {
        
        try {
            
            Document doc = Jsoup.connect(detalheURL).userAgent(AGENT)
                                                    .get();
            
            //Apenas Dados tabelados
            Elements dadosTabelados = doc.getElementsByClass("dados-tabelados");
            documento.descricao = dadosTabelados.select("strong:contains(Descrição)").next("span").text();
            documento.tipo = dadosTabelados.select("strong:contains(Tipo de documento)").next("span").text();
            documento.valorDocumento = dadosTabelados.select("strong:contains(Valor do documento)").next("span").text();
            documento.observacao = dadosTabelados.select("strong:contains(Observação do documento)").next("span").text();
            
            Elements dadosDetalhados = doc.getElementsByClass("dados-detalhados");
            
            //Apenas Dados favorecido
            
            Elements favorecidoDiv = dadosDetalhados.select("button:contains(Dados do Favorecido)").next("div");
            
            Favorecido favorecido = new Favorecido();
            favorecido.docFavorecido = favorecidoDiv.select("strong:contains(CPF/CNPJ/Outros)").next("span").text();
            favorecido.nome = favorecidoDiv.select("strong:contains(Nome)").next("span").text();
            favorecido.url = favorecidoDiv.select("strong:contains(CPF/CNPJ/Outros)").next("span").select("a").attr("abs:href");
            
            documento.favorecido = favorecido;
            
            //Apenas Orgao Pagador
            Elements pagadorDiv = dadosDetalhados.select("button:contains(Dados do Órgão)").next("div");
            OrgaoPagador orgaoPagador = new OrgaoPagador();
            
            Elements orgapSuperiorDiv = pagadorDiv.select("strong:contains(Órgão Superior)");
            orgaoPagador.orgaoSuperiorCod = orgapSuperiorDiv.next("span").text();
            orgaoPagador.orgaoSuperiorNome = orgapSuperiorDiv.next("span").next("span").text();
            orgaoPagador.url = orgapSuperiorDiv.next("span").next("span").select("a").attr("abs:href");
           
            Elements entidadeVinculadaDiv = pagadorDiv.select("strong:contains(Órgão / Entidade Vinculada)");
            orgaoPagador.entidadeVinculadaCod = entidadeVinculadaDiv.next("span").text();
            orgaoPagador.entidadeVinculadaNome = entidadeVinculadaDiv.next("span").next("span").text();
            
            Elements unidadeGestoraDiv = pagadorDiv.select("strong:contains(Unidade Gestora)");
            orgaoPagador.unidadeGestoraCod = unidadeGestoraDiv.next("span").text();
            orgaoPagador.unidadeGestoraNome = unidadeGestoraDiv.next("span").next("span").text();
            
            Elements gestaoDiv = pagadorDiv.select("strong:contains(Gestão)");
            orgaoPagador.gestaoCod = gestaoDiv.next("span").text();
            orgaoPagador.gestaoNome = gestaoDiv.next("span").next("span").text();
            
            
            documento.orgaopagador = orgaoPagador;
            
        } catch (IOException e) {
            e.printStackTrace();
        }
        
        
    }

}
