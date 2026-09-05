package br.com.economize.service;

import br.com.economize.dto.ai.ChatTurn;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.Transaction;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.TransactionRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.ai.AiChatCaller;
import br.com.economize.service.ai.AiChatCallerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AiAssistantService {

    // EC-107: quem escolhe a chave e o provedor da chamada. Sem configuração
    // própria, o factory devolve exatamente o ChatClient do servidor que este
    // serviço construía sozinho antes — o comportamento de quem não mexeu em
    // nada é o mesmo, linha por linha do prompt.
    private final AiChatCallerFactory chatCallerFactory;
    private final UserRepository userRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final TransactionRepository transactionRepository;

    public AiAssistantService(AiChatCallerFactory chatCallerFactory,
                              UserRepository userRepository,
                              BankTransactionRepository bankTransactionRepository,
                              TransactionRepository transactionRepository) {
        this.chatCallerFactory = chatCallerFactory;
        this.userRepository = userRepository;
        this.bankTransactionRepository = bankTransactionRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Quantas linhas da carteira entram no contexto.
     *
     * <p>Nao havia teto: um {@code forEach} sobre TODAS as transacoes da
     * carteira. Quem opera com frequencia mandava centenas de linhas em cada
     * pergunta — prompt que cresce sem limite e conta que cresce junto, ja que
     * cada token e pago. As 40 mais recentes sao o que uma resposta sobre
     * "minha carteira" precisa; o resto ja esta somado no resumo.
     */
    private static final int MAX_WALLET_LINES = 40;

    /** Mesma logica das bancarias, que ja tinham teto de 15. */
    private static final int MAX_BANK_LINES = 15;

    public Mono<String> askAssistant(String email, String userQuestion) {
        return askAssistant(email, userQuestion, List.of());
    }

    /**
     * A pergunta com a conversa ate aqui.
     *
     * <p>Sem o historico o assistente nao tinha memoria nenhuma: "e no mes
     * passado?" chegava ao provedor como uma primeira pergunta solta, e a
     * resposta era necessariamente sobre nada. O dado financeiro do prompt
     * continua vindo do BANCO a cada chamada — quem manda o historico e o app,
     * mas quem responde pelos numeros e o servidor.
     */
    public Mono<String> askAssistant(String email, String userQuestion, List<ChatTurn> history) {
        return Mono.fromCallable(() -> {
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));

            List<BankTransaction> bankTxs = bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId());
            List<Transaction> walletTxs = transactionRepository.findAllByUserIdOrderByTransactionDateDesc(user.getId());

            String context = buildFinancialContext(bankTxs, walletTxs);

            String systemPromptText = """
                    Você é Nino, o assistente financeiro virtual do aplicativo Economize!.
                    Seu objetivo é ajudar o usuário a entender suas finanças, analisar gastos e dar dicas de investimentos.
                    Seja conciso, profissional, mas amigável. Responda em português do Brasil.
                    Use formatação Markdown para destacar valores e tópicos.

                    Aqui estão os dados financeiros atuais do usuário para contexto:
                    {context}

                    Regras:
                    - Baseie-se estritamente nos dados fornecidos.
                    - Se o usuário perguntar algo fora do escopo financeiro, recuse educadamente.
                    - Não recomende compra/venda direta de ativos específicos, apenas dê orientações gerais.
                    """;

            // render() em vez de createMessage(): produz o MESMO texto de sistema
            // que o SystemPromptTemplate montava, só que como String — é o que o
            // AiChatCaller consome, seja ele o do servidor ou o do usuário
            String systemPrompt = new SystemPromptTemplate(systemPromptText)
                    .render(Map.of("context", context));

            // O assistente ACEITA cair na chave do servidor: é o comportamento
            // que o APK publicado conhece e não pode mudar para quem não
            // configurou nada. Por isso resolve(..., true) sempre traz um caller.
            AiChatCaller caller = chatCallerFactory.resolve(user, true)
                    .orElseThrow(() -> new IllegalStateException("Nenhum caminho de IA disponível"));

            log.info("Enviando prompt para a IA para o usuário: {} ({}, {} fala(s) de contexto))",
                    email, caller.describe(), history.size());
            return caller.complete(systemPrompt, history, userQuestion);

        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String buildFinancialContext(List<BankTransaction> bankTxs, List<Transaction> walletTxs) {
        StringBuilder sb = new StringBuilder();

        BigDecimal totalIncome = bankTxs.stream()
                .filter(t -> "CREDIT".equals(t.getType()))
                .map(BankTransaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalExpense = bankTxs.stream()
                .filter(t -> "DEBIT".equals(t.getType()))
                .map(BankTransaction::getAmount)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        sb.append("--- RESUMO BANCÁRIO ---\n");
        sb.append("Total Entradas: R$ ").append(totalIncome).append("\n");
        sb.append("Total Saídas: R$ ").append(totalExpense).append("\n");
        sb.append("Últimas transações bancárias:\n");

        bankTxs.stream().limit(MAX_BANK_LINES).forEach(t -> {
            sb.append(String.format("- %s | %s | R$ %s\n", t.getDate().toLocalDate(), t.getDescription(), t.getAmount()));
        });

        sb.append("\n--- CARTEIRA DE INVESTIMENTOS ---\n");
        if (walletTxs.isEmpty()) {
            sb.append("O usuário não possui investimentos cadastrados.\n");
        } else {
            walletTxs.forEach(t -> {
                sb.append(String.format("- %s: %s cotas (Preço médio: R$ %s)\n", t.getAssetCode(), t.getQuantity(), t.getPriceAtTransaction()));
            });
        }

        return sb.toString();
    }
}
