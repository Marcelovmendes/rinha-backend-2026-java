# Rinha de Backend 2026: Detecção de Fraude com Java + IVFPQ

> Submissão para a [**Rinha de Backend 2026**](https://github.com/zanfranceschi/rinha-de-backend-2026):
> uma competição em que cada participante constrói um backend dentro de um
> hardware especifico (**1 CPU e 350 MB de RAM no total**) e a
> pontuação é medida em termos de **latência** e **precisão da detecção de
> fraude**.

A abordagem: cada transação vira um **vetor de 14 números** e é classificada
pelos 5 vizinhos mais próximos entre 3 milhões de transações rotuladas. Isso
é **busca vetorial**, a mesma técnica por trás de recomendação e busca
semântica em LLMs.

---

## O que esse backend faz

Recebe transações financeiras (POST `/fraud-score`) e responde se a 
transação parece fraude ou legítima. A decisão é tomada comparando a
transação com **3 milhões de transações de referência já rotuladas**, usando
busca vetorial.

```
Cliente -> POST /fraud-score -> { "approved": false, "fraud_score": 0.6 }
                                   ^                  ^
                                   bloqueia?          quão "fraude" parece (0..1)
```
## Stack

- **Java 25 + GraalVM `native-image`**: compilamos o Java pra um binário
  nativo, sem JVM em runtime, startup quase instantâneo.
- **Servidor HTTP custom**: `ServerSocketChannel` + parser HTTP/1.1 + Virtual Threads. Sem framework.
- **HAProxy** como load balancer em modo TCP.
- **Unix sockets** entre HAProxy e as APIs (mais rápido que TCP).
- **Jackson** para JSON: parser tradicional `readValue()` (streaming
  regrediu com Virtual Threads).
- **Algoritmo: IVFPQ + rerank int16**, explicado abaixo.

---

### Pipeline de busca vetorial

 A solução é um **índice aproximado** que busca + um **rerank** que corrige no fim.

```
Query (vetor 14-D)
   |
   v
[1] Centroides:    encontra os 24 "bairros" mais próximos da query
                   (K-Means agrupou os 3M vetores em 1024 bairros).
   |
   v
[2] PQ ADC:        em cada bairro, calcula distância aproximada para os
                   ~3000 vetores via lookup tables (cada vetor codificado
                   em 7 bytes ao invés de 56). Pega os top-100 candidatos.
   |
   v
[3] Rerank int16:  recalcula distância EXATA (L2) sobre os top-100 com
                   vetores quantizados em int16 (tentei com int8 mas a precisão era menor).
                   Pega os 5 mais próximos verdadeiros.
   |
   v
[4] Decisão:       conta quantos dos top-5 são fraude. 3 ou mais? Bloqueia.
```

### Pré-processamento offline

Tentei colocar tudo que dá pra calcular antes do startup:

| Arquivo | Tamanho | O que é                                         |
|---|---|-------------------------------------------------|
| `centroids.bin` | 56 KB | Os 1024 do K-Means                              |
| `codebooks.bin` | 14 KB | Tabelas do Product Quantizer                    |
| `cluster_codes.bin` | 21 MB | 3M vetores em códigos PQ de 7 bytes             |
| `cluster_ids.bin` | 12 MB | Mapeamento de posição para ID original          |
| `vectors_int16.bin` | 84 MB | 3M vetores quantizados em int16 (rerank)        |
| `labels.bin` | 375 KB | Bitmap: cada vetor é fraude (1) ou legítimo (0) |


---

### Por que servidor HTTP custom em vez de Spring Boot / Helidon / Netty?

Dois motivos:

1. **Frameworks adicionam muitas dependências** (DI, service
      registry, métricas), o que aumenta o tamanho do binário e o tempo de
      build do native-image.

2. **`jdk.httpserver` (a HTTP do JDK) não suporta unix sockets.** Como
   queríamos zero overhead de TCP entre HAProxy e API, precisávamos de um
   servidor que conseguisse `bind` em path Unix. (Obrigado Thiago pela dica para usar HAProxy com Unix Socket rsrs).
3. Motivo bonus: Eu queria fazer na unha um servidor http :p

Nosso servidor tem ~300 linhas, suporta unix sockets, parsea HTTP/1.1 e
funciona com Virtual Threads.



## Como rodar localmente

### Pré-requisitos

- Docker e Docker Compose
- [k6](https://k6.io/docs/get-started/installation/) (para os testes de carga)
- ~2 GB de RAM livre (durante o build do native-image)
- O arquivo `references.json.gz` da Rinha 2026 colocado em `resources/`
  ([baixar do repo oficial](https://github.com/zanfranceschi/rinha-de-backend-2026/tree/main/resources))

### Build da imagem (~16 min na primeira vez)

```bash
docker build -t marcelocortess/rinha-java:latest .
```

O build executa o k-means, treina o Product Quantizer e quantiza tudo
DEMORA MUITO

### Subir a stack

```bash
docker compose up -d
docker compose logs -f
# Espera aparecer: "warmup: 150 queries in ~300ms"
```

### Testar

Smoke test (5 requests, valida correção básica):
```bash
k6 run test/smoke.js
```

Stress test (ramping 1 → 900 RPS por 120s):
```bash
k6 run test/test.js
cat test/results.json | python3 -m json.tool
```

### Parar

```bash
docker compose down
```

### Imagem pública pronta

Se quiser pular o build, a imagem está pública no Docker Hub:
`marcelocortess/rinha-java:latest`. Basta `docker compose up -d` (o
`docker-compose.yml` já referencia ela).

---

## Estrutura do projeto

```
src/main/java/dev/marcelovitor/rinha/
├── RinhaBackendApplication.java    # main: carrega índice, faz warmup, abre HTTP
├── http/                            # servidor HTTP custom (ServerSocketChannel)
├── ScoreHandler.java                # parse da request -> vetor -> busca -> resposta
├── knn/
│   ├── Vectorizer.java              # 14-D normalização (regras determinísticas)
│   ├── IvfIndex.java                # busca: NPROBE -> PQ ADC -> rerank int16 -> top-5
│   ├── KMeans.java                  # K-Means++ (pro build apenas)
│   └── ProductQuantizer.java        # quantização vetorial
├── store/
│   ├── DataPreprocessor.java        # offline: gz -> bins
│   └── IvfBuilder.java              # offline: k-means + PQ + quantização int16
└── model/                           # Jackson + JsonProperty
```

---


## Sobre mim

**Marcelo Vitor Mendes**
Engenheiro de Software backend. 
- LinkedIn: [linkedin.com/in/marcelovmendes](https://www.linkedin.com/in/marcelovmendes)
- Email: marcelovmendescontato@gmail.com
- GitHub: [@Marcelovmendes](https://github.com/Marcelovmendes)

---

## Licença

[MIT](LICENSE)
