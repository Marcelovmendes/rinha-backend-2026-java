# Rinha de Backend 2026: Detecção de Fraude com Java + IVF

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
- **Algoritmo: IVF puro + bbox repair (int16, SCALE=10000)**, explicado abaixo.

---

### Pipeline de busca vetorial

K-Means agrupa os 3M vetores em 1024 clusters no build. Cada cluster
tem um centroide e uma bounding box (min/max por dimensão). Tudo é
armazenado em `int16` com `SCALE=10000`. A busca por request:

```
Query (vetor 14-D, short[14])
   |
   v
[1] Best cluster:   varre os 1024 centroides e escolhe o mais próximo.
   |
   v
[2] Scan do cluster: percorre os vetores do cluster e mantém um top-5
                     por distância. Early-exit por dimensão: assim que
                     a soma parcial passa do pior do top-5, abandona
                     o vetor.
   |
   v
[3] bbox repair:    para cada outro cluster, calcula a distância da
                    query até a bounding box. Se essa distância já é
                    maior que o pior do top-5, o cluster é skippado.
                    Os que sobram são escaneados igual ao passo [2].
   |
   v
[4] Decisão:        conta fraudes no top-5. 3 ou mais bloqueia.
```

### Pré-processamento offline

Um arquivo único com tudo flat e cluster-major:

| Arquivo | Tamanho | O que é |
|---|---|---|
| `index.bin` | ~106 MB | header + offsets + centroides + bboxMin/Max + 3M vetores int16 + labels |

Layout interno: `[header][offsets k+1][centroids k×14][bboxMin k×14][bboxMax k×14][rows n×14][labels n]`. Clusters acima de 5000 vetores são divididos no build (cluster splitting).


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

O build executa o k-means, faz cluster splitting, calcula bboxes e
quantiza tudo em int16. DEMORA MUITO.

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
│   ├── IvfIndex.java                # busca: best cluster -> scan -> bbox repair -> top-5
│   ├── IndexHeader.java             # magic, versão, dims, scale do index.bin
│   └── KMeans.java                  # K-Means++ (pro build apenas)
├── store/
│   ├── DataPreprocessor.java        # offline: gz -> bins
│   └── IvfBuilder.java              # offline: k-means + cluster splitting + bboxes
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
