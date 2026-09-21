# Hopr — Đánh giá kiến trúc & Lộ trình Production

> Phạm vi soát xét: toàn bộ source ở nhánh `main` của [github.com/pcaokhai/Hopr](https://github.com/pcaokhai/Hopr), đọc trực tiếp qua clone repo.
> **Cập nhật (mới nhất):** đã chốt hướng thay MongoDB bằng ScyllaDB (mục 3) và gộp thêm lộ trình phát triển dần từ một review khác vào roadmap 6 giai đoạn (mục 6).

Hopr chạy tốt ở local — nhưng ba lỗ hổng đang âm thầm phá vỡ đúng lời hứa "collision-free, production-grade" của README. Kiến trúc phân lớp sạch, ADR có ghi rõ lý do, Testcontainers cho test tích hợp — nền đã khá vững để làm bệ phóng học system design. Đi sâu vào luồng ghi dữ liệu và cấu hình worker ID thì lộ ra vài lỗi đúng loại mà một buổi phỏng vấn system design sẽ hỏi thẳng.

**Snapshot:** 3 microservices + gateway + config-server · Java 25 · Spring Boot 4.1 · ScyllaDB (kế hoạch, 3 node RF=3) + Redis Cluster 6 node · JaCoCo gate ≥85%

**Số liệu nhanh:** 3 lỗi nghiêm trọng (2 đã có hướng giải quyết qua ScyllaDB) · 5 khoảng trống mức cao · ~15 vấn đề mức vừa/nhỏ · 6 giai đoạn lộ trình phát triển dần

---

## Mục lục

1. [Những gì đã làm tốt](#1-những-gì-đã-làm-tốt)
2. [Vấn đề nghiêm trọng (Critical)](#2-vấn-đề-nghiêm-trọng-critical)
3. [Quyết định kiến trúc: ScyllaDB thay MongoDB](#3-quyết-định-kiến-trúc-scylladb-thay-mongodb)
4. [Vấn đề mức cao (High)](#4-vấn-đề-mức-cao-high)
5. [Các nhóm vấn đề còn lại](#5-các-nhóm-vấn-đề-còn-lại)
6. [Lộ trình 6 giai đoạn](#6-lộ-trình-6-giai-đoạn)
7. [Kết luận](#7-kết-luận)

---

## 1. Những gì đã làm tốt

Trước khi soi lỗi, cần ghi nhận: đây không phải một codebase vibe-code ngẫu nhiên. Vài quyết định thiết kế ở đây thật sự đúng chất production và đáng giữ lại khi refactor.

- **Tách use case / infra rõ ràng theo từng service** — Mỗi service có tầng `application` chứa logic thuần (validator, resolver, use case) tách biệt khỏi `infra/router` và `infra/exceptionhandler` — dễ unit test, dễ đọc, đúng tinh thần clean architecture ở quy mô nhỏ.
- **ADR thật, có ngày, có "supersede" lẫn nhau** — `docs/ADR/0001` và `0002` ghi lại đúng tinh thần Architecture Decision Record: nêu lý do bỏ Eureka, đổi sang Config Server + discovery gốc của nền tảng, và tự cập nhật khi quyết định cũ bị thay thế. Đây là kỹ năng nhiều team production còn thiếu.
- **Redis Cluster 3 master + 3 replica là lựa chọn HA thật sự** — Không phải Redis đơn lẻ giả lập — cluster mode có sharding + failover tự động khi một master chết. Đây là tầng duy nhất trong hệ thống hiện tại thực sự chịu được lỗi phần cứng.
- **Testcontainers cho integration test, không mock MongoDB** — `BaseIntegrationTest` dựng `MongoDBContainer` thật thay vì mock repository — test tích hợp phản ánh đúng hành vi driver Mongo, kể cả các quirk mà mock sẽ che giấu (chính là quirk gây ra lỗi Critical #1 bên dưới).
- **k8s manifest có startup / readiness / liveness probe tách riêng** — Không gộp chung một probe — `shortener-service.yaml` có cả ba loại với threshold hợp lý, đúng thực hành k8s khuyến nghị cho service khởi động chậm (JVM + Config Server fetch).

---

## 2. Vấn đề nghiêm trọng (Critical)

Ba lỗi dưới đây không phải "thiếu tính năng" — chúng phá vỡ đúng lời hứa cốt lõi của một URL shortener: một short key phải luôn trỏ về đúng một long URL, mãi mãi.

### 2.1 Race condition khi tạo alias tùy chỉnh — request sau âm thầm ghi đè request trước

`AliasAvailabilityValidator.java` kiểm tra `urlRepository.findById(alias)` rồi mới cho phép `DbCacheSaver.saveUrlMapping()` gọi `urlRepository.save()` ở một bước riêng, không có transaction hay lock nào giữ hai bước đó nguyên tử. Đây là race kinh điển: check-then-act (TOCTOU).

Tệ hơn: Spring Data MongoDB's `save()` khi entity đã có `@Id` sẽ thực hiện *upsert* theo `_id`, không phải insert-fail-nếu-trùng. Nên khi hai request cùng đặt alias `"black-friday"` gần như đồng thời, cả hai đều pass validate, cả hai đều save thành công — người đến sau thắng, URL của người đến trước biến mất không một cảnh báo nào.

- **Rủi ro:** Chiếm đoạt alias (link hijacking): kẻ tấn công spam trùng alias phổ biến để cướp quyền điều hướng link của người khác — nghiêm trọng hơn cả bug, gần với lỗ hổng bảo mật.
- **Hướng sửa:** Tạo unique index thật trên `_id`/`alias` (Mongo đã tự có qua `_id`), nhưng đổi `save()` thành `insert()` ở bước persist, bắt `DuplicateKeyException` và map sang `409` — để Mongo, chứ không phải một `findById` trước đó, là trọng tài cuối cùng cho tính duy nhất.
- **Góc học:** Bài học nhập môn về concurrency control trong distributed system: tại sao "check rồi mới act" luôn sai khi có nhiều instance, và vì sao invariant về tính duy nhất phải nằm ở tầng lưu trữ (unique constraint / conditional write) chứ không phải ở tầng logic ứng dụng.
- **Cập nhật:** Đã chốt hướng: chuyển sang ScyllaDB và dùng `INSERT ... IF NOT EXISTS` (Lightweight Transaction) thay cho cặp check-rồi-save — xem [mục 3](#3-quyết-định-kiến-trúc-scylladb-thay-mongodb). Đây là cách giải quyết tận gốc bằng conditional write ở tầng storage, không phải vá thêm logic ứng dụng.

### 2.2 Snowflake worker ID được suy ra từ IP/hostname — không có gì đảm bảo duy nhất khi scale keygen-service

`DefaultNodeInfoProvider.java` lấy `datacenterId` từ `hostname.hashCode() % 32` và `machineId` từ byte cuối của địa chỉ IPv4 non-loopback, AND với `0x1F` (chỉ còn 5 bit, 32 giá trị). Không có coordinator nào (Zookeeper/etcd/Redis lease) cấp phát worker ID — mỗi instance tự "đoán" ID của chính nó.

Trong Docker Compose/Swarm/k8s, container thường nhận IP tuần tự trong cùng subnet — byte cuối trùng nhau giữa hai pod là hoàn toàn có thể, đặc biệt khi `keygen-service` được scale lên nhiều replica đúng như README quảng cáo ("horizontal scalability"). Khi trùng worker ID, hai instance có thể sinh cùng một Snowflake ID trong cùng millisecond → cùng short key → rơi vào đúng lỗi upsert-ghi-đè ở trên, lần này là với auto-generated key.

- **Rủi ro:** Chính tính năng được README gọi là "Decentralized, Collision-Free Key Generation" lại là nơi dễ collision nhất khi scale ngang — mâu thuẫn trực tiếp với lời hứa kiến trúc.
- **Hướng sửa:** Cấp phát worker ID qua một nguồn có điều phối: Redis `INCR` + lease TTL khi pod khởi động, hoặc dùng `StatefulSet` ordinal trong k8s (pod-0, pod-1... → worker ID tất định), thay vì suy luận từ mạng.
- **Góc học:** Bài toán generate ID phân tán không có coordinator là chủ đề gốc của cả họ thuật toán (Snowflake, ULID, Flake, ticket server) — đây là chỗ để so sánh trade-off giữa "tự suy luận ID" (nhanh, không cần phối hợp, nhưng dễ collision) và "coordinator cấp phát" (an toàn hơn, nhưng có thêm một dependency phải luôn sẵn sàng).

### 2.3 MongoDB chạy đơn instance — không replica set, không backup, một điểm chết duy nhất cho toàn bộ dữ liệu

Cả `docker-compose.yml` lẫn `stack.yml` (bản "production-ready" theo README) đều chạy `mongodb:7.0` với `replicas: 1`, một volume duy nhất, không replica set, không snapshot/backup job nào trong repo. Trong khi Redis được đầu tư hẳn 6 node cluster có failover, thì Mongo — nơi giữ bản ghi *vĩnh viễn* duy nhất của mọi URL mapping — lại là mắt xích yếu nhất.

Container hoặc volume hỏng là mất toàn bộ dữ liệu, kể cả cache Redis khi đó cũng vô nghĩa vì không còn nguồn sự thật để repopulate.

- **Rủi ro:** Mất dữ liệu vĩnh viễn khi node/volume hỏng; không có cách phục hồi (không backup) hay đọc mở rộng (không read replica) khi traffic tăng.
- **Hướng sửa:** Tối thiểu: MongoDB Replica Set 3 node (đúng như README từng nhắc "MongoDB Atlas / Mongo 7.0" — Atlas mặc định đã là replica set, nhưng bản self-host trong compose/stack thì chưa). Thêm job `mongodump` định kỳ ra external storage.
- **Góc học:** So sánh trực tiếp trong cùng một repo: vì sao Redis (cache, có thể mất và tự phục hồi từ Mongo) được đầu tư HA hơn Mongo (nguồn sự thật, mất là mất thật) lại là bài học ngược đời hay gặp — ưu tiên HA nên đi theo "cái gì mất sẽ đau nhất", không phải "cái gì dễ cluster hóa nhất".
- **Cập nhật:** Đã chốt hướng: thay vì "vá" Mongo bằng Replica Set, chuyển thẳng sang ScyllaDB triển khai 3 node ngay từ đầu (RF=3) — cùng một lần dựng hạ tầng vừa có HA thật, vừa giải quyết luôn Critical #1. Xem [mục 3](#3-quyết-định-kiến-trúc-scylladb-thay-mongodb).

---

## 3. Quyết định kiến trúc: ScyllaDB thay MongoDB

Access pattern của Hopr là point-lookup thuần theo `short_key` — đọc/ghi theo một khóa duy nhất, không join, không query quan hệ sâu. Đây đúng là bài toán wide-column store được sinh ra để giải, và giống hệt cách Redis Cluster đã được đầu tư đúng: nhiều node ngay từ đầu, không phải một instance đơn lẻ "vá" thêm HA sau.

**Vì sao đáng đổi:** ScyllaDB (tương thích CQL với Cassandra) giải quyết trực tiếp hai trong ba lỗi Critical mà không cần thêm cơ chế phụ nào ở tầng ứng dụng:

| MongoDB hiện tại | ScyllaDB (hướng đã chọn) |
|---|---|
| Đơn instance, không replica set → single point of failure cho toàn bộ dữ liệu | 3 node, `RF=3` ngay từ ngày đầu — cùng triết lý đã áp dụng đúng cho Redis Cluster |
| Tính duy nhất dựa vào check-rồi-save ở tầng ứng dụng → race condition (Critical #1) | `INSERT ... IF NOT EXISTS` (Lightweight Transaction / Paxos) — tính duy nhất do storage đảm bảo, không cần check-rồi-act |
| Flexible schema — nhưng `UrlMapping` chỉ có 3 field cố định nên lợi thế này chưa được dùng tới | TTL per-row gốc, counter table cho click count — vừa vặn với nhu cầu analytics ở Giai đoạn 5 |

### Schema CQL đề xuất

Điểm khác biệt lớn nhất khi chuyển từ Mongo sang họ Cassandra/Scylla: phải thiết kế bảng theo *từng câu query* sẽ chạy, không phải theo "object nhìn tự nhiên" như document. Bảng đếm click **không** được trộn cột thường với cột `counter` — đây là ràng buộc thật của Scylla, nên tách hẳn ba bảng:

```sql
-- keyspace + bảng chính — Replication factor 3, cùng mức HA đã có ở Redis Cluster
CREATE KEYSPACE hopr WITH replication = {
  'class': 'NetworkTopologyStrategy', 'replication_factor': 3
};

CREATE TABLE hopr.urls (
  short_key   text PRIMARY KEY,
  long_url    text,
  alias       text,
  owner_id    text,
  status      text,
  created_at  timestamp,
  expires_at  timestamp
);

-- Tạo mapping: LWT thay cho check-rồi-save, tự fail nếu short_key đã tồn tại
INSERT INTO hopr.urls (short_key, long_url, alias, status, created_at)
VALUES (?, ?, ?, 'active', toTimestamp(now()))
IF NOT EXISTS;
```

```sql
-- bảng đếm click (counter table riêng, bắt buộc)
CREATE TABLE hopr.url_click_counts (
  short_key text PRIMARY KEY,
  clicks    counter
);

UPDATE hopr.url_click_counts SET clicks = clicks + 1 WHERE short_key = ?;
```

```sql
-- event log cho phân tích (partition theo ngày để tránh hot partition)
CREATE TABLE hopr.url_click_events (
  short_key   text,
  day         date,
  clicked_at  timestamp,
  referrer    text,
  PRIMARY KEY ((short_key, day), clicked_at)
) WITH CLUSTERING ORDER BY (clicked_at DESC);
```

### Consistency level — chỗ trả giá latency đúng nơi cần

| Thao tác | Consistency level | Vì sao |
|---|---|---|
| Ghi có alias tùy chỉnh | `SERIAL` / `LOCAL_SERIAL` | LWT chạy qua Paxos nên tốn thêm round-trip — chấp nhận được vì đây là path ít traffic hơn hẳn so với redirect, đổi lại loại bỏ hẳn Critical #1 thay vì chỉ giảm xác suất. |
| Ghi key tự sinh (sau khi sửa Critical #2) | `LOCAL_QUORUM` | Không cần LWT vì worker-ID coordinator đã đảm bảo không trùng — plain `INSERT` là đủ, giữ latency thấp cho path phổ biến nhất. |
| Đọc khi cache-miss ở resolver | `LOCAL_ONE` | Redis vẫn là nguồn sự thật cho hot path; Scylla chỉ được hỏi khi miss, nên ưu tiên latency thấp hơn là strong consistency ở bước này. |

### Đường đi trong Spring — không phải viết lại từ đầu

Vì `UrlRepository` hiện tại chỉ là `extends MongoRepository<UrlMapping, String>`, phần lớn tầng use case (`ShortenerUseCase`, `ResolverUseCase`) không cần đổi — đổi tập trung ở tầng repository/entity:

1. Đổi dependency: `spring-boot-starter-data-mongodb` → `spring-boot-starter-data-cassandra` (Scylla tương thích wire protocol CQL nên driver Cassandra dùng thẳng được).
2. `UrlMapping`: bỏ `@Document`/`@Id` kiểu Mongo, thêm `@Table("urls")` + `@PrimaryKey`.
3. `UrlRepository extends CassandraRepository<UrlMapping, String>`.
4. Gộp `AliasAvailabilityValidator` + `DbCacheSaver` thành một thao tác `insertIfNotExists()` duy nhất dùng CQL `IF NOT EXISTS` — xóa hẳn cửa sổ race thay vì thu hẹp nó.
5. `docker-compose.yml`/`stack.yml`/Helm chart: thay service `mongodb` bằng 3 service `scylla-node-{1,2,3}` — cùng khuôn mẫu đã quen với 6 node Redis.
6. `config-repo/*.yml`: đổi `spring.mongodb.uri` → `spring.cassandra.contact-points` / `keyspace-name`.
7. `BaseIntegrationTest`: đổi `MongoDBContainer` → module Testcontainers Cassandra trỏ image `scylladb/scylla` — giữ nguyên khoản đầu tư Testcontainers đã có.

> **Đánh đổi cần biết:** Scylla chỉ thật sự có giá trị từ 3 node trở lên (để RF=3 có ý nghĩa) — nặng hơn một container Mongo đơn lẻ khi chạy local, nhưng repo đã quen mẫu này qua Redis Cluster 6 node. Đổi lại, mất luôn lợi thế "query tùy ý" của document DB — mọi bảng phải thiết kế theo query trước (query-first modeling), một trong những bài học tự nó đã đáng giá cho mục tiêu "database optimization" của bạn.

---

## 4. Vấn đề mức cao (High)

Chưa gây mất dữ liệu ngay, nhưng chặn đường lên production.

### 4.1 Gọi keygen-service không timeout, block thread, nuốt mọi exception

`KeyGenClient.java` dùng `WebClient` (reactive) nhưng gọi `.block()` ngay trong luồng xử lý request của Spring MVC (blocking) — không set `.timeout()` nào. Nếu keygen-service treo (không crash, chỉ chậm), request ở shortener-service sẽ block vô thời hạn, chiếm dụng thread trong pool Tomcat hữu hạn. Dưới tải, vài chục request treo là đủ làm cạn thread pool và service "trông như chết" dù không có exception nào được ném ra.

Thêm nữa, `catch (Exception e)` ở `callKeygenService()` nuốt mọi loại lỗi (network, timeout, serialization, kể cả lỗi lập trình) thành cùng một `Optional.empty()` — không phân biệt được "keygen thật sự down" với "response format sai" khi debug.

- **Hướng sửa:** Thêm `.timeout(Duration)` trên chuỗi reactive; giới hạn phạm vi catch; cân nhắc Resilience4j (`CircuitBreaker` + `TimeLimiter`) thay vì try/catch thủ công.
- **Góc học:** Đây chính là bài học "cascading failure": một dependency chậm (không cần chết hẳn) có thể kéo sập cả service gọi nó, nếu không có timeout + bulkhead. Circuit breaker pattern tồn tại chính xác để giải bài toán này.

### 4.2 Không validate `longUrl`, không auth, không quota — `/shorten` mở hoàn toàn cho ẩn danh

`ShortenRequest` là record trần, không một ràng buộc `@NotBlank`/`@URL` nào. Bất kỳ chuỗi nào cũng được lưu làm `longUrl` rồi resolver sẽ 307-redirect thẳng tới đó. Kết hợp với việc `/shorten` không yêu cầu xác thực và rate limit ở Nginx chỉ tính theo IP (dễ vượt qua bằng nhiều IP/proxy), hệ thống là một open redirect/phishing vector kinh điển mà chính các URL shortener thật (bit.ly, TinyURL) từng phải xử lý bằng domain blocklist + kiểm duyệt.

- **Hướng sửa:** Validate scheme (chỉ http/https), độ dài tối đa; thêm ít nhất API key theo client cho `/shorten`; cân nhắc domain reputation check (danh sách chặn hoặc gọi Safe Browsing API) trước khi persist.

### 4.3 Không có observability nào vượt quá `/actuator/health`

Cả ba service chỉ expose `health,info` (`config-repo/*.yml`). Không có `micrometer-registry-prometheus`, không distributed tracing (không OpenTelemetry/Zipkin), không correlation ID xuyên suốt chuỗi gọi Gateway → Shortener → Keygen hay Gateway → Resolver → Redis/Mongo. Với một hệ thống 3 hop mạng cho một request đơn giản, khi latency tăng đột biến ở production sẽ không có cách nào biết hop nào chậm mà không SSH vào từng container đọc log rời rạc.

- **Hướng sửa:** Thêm `micrometer-tracing` + `micrometer-registry-prometheus`, expose `/actuator/prometheus`, propagate `traceId` qua header giữa các service call (WebClient filter tự động làm việc này nếu bật Micrometer Tracing).
- **Góc học:** "Production-grade" thường được định nghĩa không phải bởi throughput mà bởi MTTR (mean time to recovery) — và MTTR phụ thuộc gần như hoàn toàn vào observability có sẵn từ trước, không phải thêm vào lúc sự cố xảy ra.

### 4.4 Container chạy bằng root, credential Mongo hardcode dạng plaintext

Cả 3 `Dockerfile` (shortener/resolver/keygen) không có dòng `USER` nào — mặc định chạy process Java bằng `root` bên trong container. `docker-compose.yml` và `stack.yml` đều hardcode `MONGO_INITDB_ROOT_USERNAME: root` / `PASSWORD: password` trực tiếp trong file thay vì qua secret.

- **Hướng sửa:** Thêm `RUN useradd -r appuser && USER appuser` vào Dockerfile; chuyển Mongo credential sang Docker secret (Swarm) / k8s `Secret` (đã có `k8s/hopr-chart/templates/secret.yaml` — nhưng Compose/Swarm chưa theo cùng chuẩn).

### 4.5 Helm chart hardcode `replicas: 1`, không resource requests/limits, không HPA

README quảng cáo "horizontal scalability" nhưng chính `k8s/hopr-chart/templates/shortener-service.yaml` (và tương tự cho resolver/keygen) hardcode `replicas: 1` ngay trong template thay vì đọc từ `values.yaml`, và không khai báo `resources.requests/limits` nào. Không có `HorizontalPodAutoscaler` nào trong chart. Nơi lẽ ra phải chứng minh câu chuyện "scale ngang" lại là nơi chưa wire xong.

- **Hướng sửa:** Tham số hóa `replicas` qua `values.yaml`, thêm CPU/memory requests+limits (bắt buộc để k8s scheduler và HPA hoạt động đúng), thêm `HorizontalPodAutoscaler` dựa trên CPU hoặc custom metric (RPS qua Prometheus adapter).

---

## 5. Các nhóm vấn đề còn lại

Không nguy hiểm bằng nhóm trên, nhưng mỗi mục đều là một khoảng cách cụ thể giữa "chạy được ở local" và "vận hành được ở production".

### Resilience & luồng dữ liệu
- **Không circuit breaker / retry chuẩn hóa** — Toàn bộ resilience hiện là try/catch thủ công trong `KeyGenClient`. Không có thư viện như Resilience4j để chuẩn hóa retry, backoff, circuit breaker qua các service call trong tương lai.
- **Không có tính idempotent cho `POST /shorten`** — Client retry (do timeout mạng) sẽ tạo thêm một bản ghi/alias mới cho cùng một long URL — không có idempotency key hay dedup theo `longUrl` đã tồn tại.
- **Field `alias` lưu trùng lặp với `shortKey`** — Khi dùng custom alias, `shortKey == alias` — field `alias` trong `UrlMapping` không phục vụ truy vấn nào khác, chỉ tốn storage và gây nhầm lẫn khi đọc code.

### Dữ liệu & thiết kế DB
- **Không TTL / archiving cho URL mapping** — Mọi mapping sống vĩnh viễn trong `urls` collection. Không có khái niệm hot/cold storage.
- **Không endpoint list/update/delete/expire cho URL đã tạo** — Dashboard ở frontend đang mock hoàn toàn phần này (đã ghi rõ trong `frontend/README.md`) vì backend chưa có API tương ứng — người tạo link không có cách nào quản lý lại link của mình.
- **Không click analytics / event log** — Resolver chỉ redirect, không ghi nhận sự kiện click nào — đây là chỗ tự nhiên nhất để đưa Kafka vào (xem Giai đoạn 5).
- **Cache TTL 12h hardcode trong code, không nằm trong Config Server** — `CacheConfig.java` ở cả shortener lẫn resolver hardcode `Duration.ofHours(12)` và bị trùng lặp y hệt giữa hai service.

### Bảo mật & vận hành mạng
- **Không TLS/HTTPS ở bất kỳ đâu** — Nginx chỉ listen port 80 plaintext. Chấp nhận được cho local dev, nhưng "production-grade" cần TLS termination.
- **`UrlShortenerExceptionHandler` extends nhầm class reactive** — File này extends `org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler` (WebFlux) trong khi `ResolverExceptionHandler` dùng đúng bản `web.servlet` (MVC). Vẫn chạy được vì Spring resolve theo annotation, nhưng là inconsistency dễ gây nhầm.
- **Không có exception handler fallback (catch-all)** — Lỗi ngoài dự kiến (Mongo mất kết nối, NPE...) sẽ rơi về response mặc định của Spring thay vì một contract lỗi nhất quán.

### Testing & CI
- **JaCoCo gate chỉ tính ở mức BUNDLE, không theo class** — 85% coverage là tổng toàn module — một class quan trọng (như `DefaultNodeInfoProvider`) có thể gần như không được test trong khi các class dễ test khác kéo điểm trung bình lên. Đúng là lỗ hổng worker-id ở trên không hề bị test nào bắt được.
- **CI chưa build/publish Docker image hay scan image** — Cả hai workflow (`gradle-build-main.yml`, `gradle-build-pull-requests.yml`) chỉ chạy `./gradlew build`. Không bước nào build image, push registry, hay scan (Trivy/Grype).
- **Dependabot chỉ cấu hình cho Gradle** — `dependabot.yml` thiếu ecosystem `docker` (base image `eclipse-temurin`, `nginx:latest`, `redis:latest` — toàn bản `latest` không pin version) và `npm` cho `frontend/`.

---

## 6. Lộ trình 6 giai đoạn

Bản này gộp roadmap gốc với phần "phát triển dần" từ một review khác về cùng repo — hai bên trùng nhau khá nhiều nên gộp lại theo đúng thứ tự phụ thuộc, không lặp: sửa tính đúng đắn trước, quan sát được hệ thống trước khi tối ưu, siết bảo mật trước khi mở API công khai, rồi mới mở rộng sang những mảng bạn đã có kinh nghiệm sẵn (Kafka, SAGA, Outbox).

### Giai đoạn 1 — Nền dữ liệu đúng đắn: migrate sang ScyllaDB + sửa Snowflake
**Mục tiêu:** một short key luôn trỏ đúng một long URL, kể cả dưới tải đồng thời và khi scale ngang keygen-service.
- Migrate `urls` sang ScyllaDB 3 node theo schema ở mục 3, dùng `IF NOT EXISTS` cho path custom alias
- Thay cơ chế sinh worker ID bằng lease qua Redis hoặc ordinal của `StatefulSet`
- Viết script backfill dữ liệu Mongo hiện có (nếu đã có traffic thật) sang bảng Scylla mới

*Khái niệm:* lightweight transaction (Paxos) · distributed ID generation · replication factor & quorum

### Giai đoạn 2 — Quan sát được hệ thống & chịu lỗi tốt hơn
**Mục tiêu:** khi một dependency chậm hoặc chết, biết ngay ở đâu — và service gọi nó không sập theo.
- Thêm Micrometer Tracing + Prometheus, dashboard Grafana cơ bản cho 3 service + Scylla
- Thêm Resilience4j (timeout + circuit breaker) quanh `KeyGenClient`
- Cấu hình graceful shutdown (`server.shutdown=graceful`) khớp với `update_config.order: start-first` đã có trong `stack.yml`

*Khái niệm:* distributed tracing · circuit breaker · SLO / SLI

### Giai đoạn 3 — Siết bảo mật trước khi mở API công khai
**Mục tiêu:** `/shorten` không còn là open redirect vector ẩn danh, secret không còn nằm trần trong file cấu hình.
- Validate scheme + độ dài `longUrl`, thêm API key theo client cho `/shorten`
- Chuyển credential (Scylla, Redis) sang k8s `Secret`/Docker secret thay hardcode; thêm `USER` non-root vào Dockerfile
- TLS termination ở gateway (cert-manager/k8s hoặc Let's Encrypt cho Compose/Swarm), security headers thay CORS `*`

*Khái niệm:* input validation ở edge · secret management · authn/z service-to-service

### Giai đoạn 4 — Mở rộng data model & testing nâng cao
**Mục tiêu:** đưa "horizontal scalability" từ slogan trong README thành cấu hình chạy được, và bảng `urls` đủ field cho sản phẩm thật.
- Thêm `expires_at` + TTL native của Scylla, endpoint list/update/delete cho URL đã tạo
- Tham số hóa replicas trong Helm chart, thêm resource requests/limits, thêm HPA theo CPU hoặc RPS
- Testcontainers Scylla thay Mongo trong `BaseIntegrationTest`; thêm contract test shortener↔keygen và load test k6 cho redirect path, đối chiếu con số "sub-millisecond" README đang tuyên bố

*Khái niệm:* TTL ở tầng storage · query-first data modeling · contract & load testing

### Giai đoạn 5 — Đưa kiến trúc event-driven vào
**Mục tiêu:** dùng chính domain URL shortener làm bài tập thực hành Kafka/SAGA/Outbox mà hồ sơ của bạn đã có nền — hiện repo này hoàn toàn chưa chạm tới mảng này.
- Outbox pattern cho bước "persist Scylla + prime cache" hiện đang không transactional
- Publish event `UrlCreated`/`UrlClicked` qua Kafka thay vì ghi cache trực tiếp trong use case
- Consumer riêng ghi vào `url_click_counts`/`url_click_events` (đã thiết kế ở mục 3) — tách hẳn khỏi hot path redirect

*Khái niệm:* outbox pattern · event-driven architecture · Kafka consumer group

### Giai đoạn 6 — K8s production patterns & hoàn thiện domain
**Mục tiêu:** rollout không gây gián đoạn khi scale/deploy, và sản phẩm có đủ tính năng quản lý link cơ bản.
- Progressive delivery (canary/blue-green) cho rolling update thay vì chỉ `start-first`
- Pod Disruption Budget + multi-AZ awareness cho các Deployment
- API versioning + owner/multi-tenant cơ bản cho quản lý link (khớp với `owner_id` đã thêm ở Giai đoạn 4)

*Khái niệm:* progressive delivery · capacity planning · API lifecycle

---

## 7. Kết luận

Bộ khung (Gradle multi-module, Config Server, Redis Cluster, ADR, Helm chart) đã ở mức nhiều team thật còn chưa làm tới. Cái thiếu không phải là "thêm tính năng" mà là "siết lại đúng đắn" — ba lỗi Critical đều nằm ở đúng loại vấn đề mà một buổi phỏng vấn system design senior sẽ hỏi thẳng: concurrency, distributed ID, và single point of failure. Quyết định chuyển sang ScyllaDB (mục 3) giải quyết gọn hai trong ba lỗi đó bằng một lần đổi hạ tầng thay vì vá riêng lẻ.

Gợi ý làm việc: xong Giai đoạn 1 trước khi thêm bất kỳ tính năng mới nào — một hệ thống nhanh nhưng ghi đè dữ liệu người dùng thì tốc độ không còn ý nghĩa. Giai đoạn 2–4 sẽ tự nhiên dẫn tới các câu hỏi database optimization và bảo mật bạn đang muốn luyện, còn Giai đoạn 5–6 là sân chơi đúng chuyên môn sẵn có của bạn.
