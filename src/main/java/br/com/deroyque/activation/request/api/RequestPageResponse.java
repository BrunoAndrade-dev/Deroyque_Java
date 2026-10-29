package br.com.deroyque.activation.request.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Página de solicitações restrita ao consumidor autenticado e aos filtros aplicados.
 *
 * @param content pedidos da página, do mais recente para o mais antigo.
 * @param page índice da página, iniciado em zero.
 * @param size tamanho solicitado, não a quantidade efetivamente retornada.
 * @param totalElements total de pedidos do consumidor que atendem aos filtros.
 * @param totalPages total de páginas para os filtros e tamanho solicitados.
 */
public record RequestPageResponse(
        @Schema(description = "Solicitações da página atual, ordenadas por criação decrescente.") List<ActivationRequestResponse> content,
        @Schema(description = "Índice da página atual, começando em 0.", example = "0") int page,
        @Schema(description = "Tamanho solicitado para a página; não representa a quantidade efetivamente retornada.", example = "20") int size,
        @Schema(description = "Total de solicitações do consumidor que atendem aos filtros.") long totalElements,
        @Schema(description = "Total de páginas considerando os filtros e o tamanho solicitado.") int totalPages) {
}
