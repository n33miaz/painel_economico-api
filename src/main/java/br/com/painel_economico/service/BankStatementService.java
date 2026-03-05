package br.com.painel_economico.service;

import br.com.painel_economico.model.BankTransaction;
import br.com.painel_economico.model.User;
import br.com.painel_economico.repository.BankTransactionRepository;
import br.com.painel_economico.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class BankStatementService {

    private final BankTransactionRepository bankTransactionRepository;
    private final UserRepository userRepository;

    private static final Pattern STMTTRN_PATTERN = Pattern.compile("<STMTTRN>(.*?)</STMTTRN>", Pattern.DOTALL);
    private static final Pattern TRNTYPE_PATTERN = Pattern.compile("<TRNTYPE>([^<\\r\\n]+)");
    private static final Pattern DTPOSTED_PATTERN = Pattern.compile("<DTPOSTED>([^<\\r\\n\\[]+)");
    private static final Pattern TRNAMT_PATTERN = Pattern.compile("<TRNAMT>([^<\\r\\n]+)");
    private static final Pattern FITID_PATTERN = Pattern.compile("<FITID>([^<\\r\\n]+)");
    private static final Pattern MEMO_PATTERN = Pattern.compile("<MEMO>([^<\\r\\n]+)");

    public Mono<Integer> processOfxFile(String email, FilePart filePart) {
        return Mono.fromCallable(() -> userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado")))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(user ->
                DataBufferUtils.join(filePart.content())
                        .map(dataBuffer -> {
                            byte[] bytes = new byte[dataBuffer.readableByteCount()];
                            dataBuffer.read(bytes);
                            DataBufferUtils.release(dataBuffer);
                            return new String(bytes, StandardCharsets.UTF_8);
                        })
                        .flatMap(ofxContent -> parseAndSaveTransactions(user, ofxContent)));
    }

    private Mono<Integer> parseAndSaveTransactions(User user, String ofxContent) {
        return Mono.fromCallable(() -> {
            List<BankTransaction> transactionsToSave = new ArrayList<>();
            Matcher matcher = STMTTRN_PATTERN.matcher(ofxContent);
            int savedCount = 0;

            while (matcher.find()) {
                String block = matcher.group(1);

                String fitId = extract(FITID_PATTERN, block);
                String trnAmt = extract(TRNAMT_PATTERN, block);

                if (fitId != null && trnAmt != null) {
                    String cleanFitId = fitId.trim();

                    if (!bankTransactionRepository.existsByUserIdAndTransactionId(user.getId(), cleanFitId)) {
                        String type = extract(TRNTYPE_PATTERN, block);
                        String dtPosted = extract(DTPOSTED_PATTERN, block);
                        String memo = extract(MEMO_PATTERN, block);

                        BankTransaction trx = BankTransaction.builder()
                                .user(user)
                                .transactionId(cleanFitId)
                                .type(type != null ? type.trim() : "UNKNOWN")
                                .amount(new BigDecimal(trnAmt.trim()))
                                .description(memo != null ? memo.trim() : "")
                                .date(parseOfxDate(dtPosted))
                                .build();

                        transactionsToSave.add(trx);
                        savedCount++;
                    }
                }
            }

            if (!transactionsToSave.isEmpty()) {
                bankTransactionRepository.saveAll(transactionsToSave);
                log.info("Salvas {} novas transações bancárias para o usuário {}", savedCount, user.getEmail());
            } else if (savedCount == 0 && ofxContent.contains("<STMTTRN>")) {
                log.info("Todas as transações do arquivo OFX já existiam no banco de dados.");
            } else {
                throw new IllegalArgumentException("Nenhuma transação financeira encontrada no arquivo.");
            }

            return savedCount;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String extract(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private java.time.OffsetDateTime parseOfxDate(String dateStr) {
        if (dateStr == null || dateStr.length() < 8)
            return java.time.OffsetDateTime.now();
        try {
            dateStr = dateStr.trim();
            String cleanDate = dateStr.length() >= 14 ? dateStr.substring(0, 14) : dateStr.substring(0, 8) + "000000";
            LocalDateTime ldt = LocalDateTime.parse(cleanDate, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            return ldt.atOffset(ZoneOffset.UTC);
        } catch (Exception e) {
            log.warn("Erro ao fazer parse da data OFX '{}', utilizando data atual.", dateStr);
            return java.time.OffsetDateTime.now();
        }
    }

    public Flux<BankTransaction> getUserBankTransactions(String email) {
        return Mono.fromCallable(() -> {
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
            return bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId());
        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapIterable(list -> list);
    }
}