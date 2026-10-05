# Rony Spring API

Backend local de Angular para MIRA y el brazo Hiwonder JetMax. Java 21 y Spring Boot 4.1.1 atienden la API, los casos de uso, la planificación del movimiento y la persistencia MySQL. El proyecto contiene los adaptadores nativos de YOLO/PyTorch y ROS en `adapters/`: usan JSON por entrada/salida estándar, no exponen HTTP y no necesitan ejecutar `Backend-Rony-Pruebas` ni su FastAPI. El modelo y las credenciales siguen siendo recursos locales externos.

## Ejecutar en este PC

El modo predeterminado utiliza la cámara real y el JetMax físico. El arranque solo conecta y lee; los movimientos requieren **Activar funcionamiento** desde Angular. Sin hardware disponible se informa desconexión, sin sustituirlo por datos simulados. Las variables `RONY_MODE` y `RONY_ENABLE_ROBOT_MOTION`, si están definidas en el IDE o el entorno, tienen prioridad sobre estos valores predeterminados.

MySQL Server debe estar iniciado. La base local es `rony_local` en `127.0.0.1:3306`; la configuración privada de este PC está en `application-local.properties`, excluida de Git. `application.properties` admite `RONY_DB_HOST`, `RONY_DB_PORT`, `RONY_DB_NAME`, `RONY_DB_USER` y `RONY_DB_PASSWORD`; no contiene contraseñas. `run.ps1` pide la contraseña si no hay configuración local ni variable de entorno. Para otro PC configura Java 21 y `JAVA_HOME`, y el Python de visión con `RONY_PYTHON`.

```powershell
cd C:\Users\PC\Desktop\Tesis\Back-Rony\back-rony
.\run.ps1 -Mode Physical
```

La API escucha en `http://127.0.0.1:8000`. El arranque físico conecta cámara y ROS, sin mover el brazo. Angular sigue usando su proxy `/api` hacia ese puerto:

```powershell
cd C:\Users\PC\Desktop\Tesis\Web-Angular
npm start
```

Abre `http://127.0.0.1:4200/monitoring`. Los botones existentes activan, cancelan y resetean. No ejecutes al mismo tiempo el backend anterior o el notebook de movimiento. Si otro proceso ocupa el puerto, detén esa instancia antes; no cambies Angular para mantener dos controladores del brazo.

```powershell
.\run.ps1 -Mode Simulation  # Cámara y proceso virtuales en Java; no necesita Python
.\run.ps1 -Mode Live        # Cámara MIRA real y movimientos virtuales
.\run.ps1 -Mode Physical    # Cámara real y JetMax físico; requiere activar desde Angular
```

## MySQL Workbench

En Workbench crea una conexión a `127.0.0.1`, puerto `3306`, usando tu cuenta local. En otro PC ejecuta `database/create-local-database.sql` una vez para crear el esquema. En este PC ya se creó. Al iniciar Spring, Flyway aplica `src/main/resources/db/migration/V1__create_operation_history.sql`; Hibernate valida el esquema, no lo crea ni modifica automáticamente.

`database/inspect-local-data.sql` permite consultar versiones y resultados desde Workbench. `classification_operation` se reserva para clasificaciones confirmadas; los ciclos virtuales no se guardan en MySQL. El flujo actual de agarre y regreso a HOME no confirma un depósito y no crea una clasificación. `robot_grasp_cycle` almacena planes físicos terminados con pinza a 82°; no afirma presencia de objeto. El resumen de Angular cuenta clasificaciones y el estado del brazo cuenta agarres por separado. El historial sobrevive al reinicio; no se guardan frames, video, tokens ni contraseñas en la base. MySQL utiliza UTF-8 y tiempos UTC. El acceso con root corresponde a este entorno local; configura un usuario de aplicación y un usuario de migraciones para un despliegue.

## Arquitectura y estándares

Paquete base `com.back.rony.backrony`, organizado por contextos: `monitoring` (observación y ciclo virtual), `robotics` (plan y ciclo físico), `history` (registro), `security` (política de acceso) y `shared` (configuración y errores).

| Capa | Responsabilidad |
|---|---|
| `domain` | Valores y reglas puras en Java, sin Spring, JPA ni transporte. |
| `application` | Casos de uso y puertos; coordina un único capturador y un único trabajador de actuadores. |
| `infrastructure` | JPA, ejecutores, adaptadores de cámara y SSH/ROS y configuración externa. |
| `presentation` | Controladores REST, validación de cuerpos y documentación OpenAPI. |

Clases y records usan PascalCase; paquetes minúsculas; métodos y campos lowerCamelCase; constantes UPPER_SNAKE_CASE. Inyección por constructor, valores inmutables, recursos cerrados al apagar, JSON estricto, errores sin stack traces públicos y transacciones solo para persistencia. Los enums de comandos se validan antes de entrar a los casos de uso; el navegador no puede enviar posiciones, ángulos o shell. Las nuevas migraciones se agregan como `V2__...sql`, sin reescribir una versión aplicada. El README es la única documentación Markdown del proyecto.

## API compatible y documentación

OpenAPI: `/api/v1/openapi.json`. Swagger UI: `/api/docs`. Los POST usan `Content-Type: application/json` y `X-Rony-Control: 1`. Un HTTP 200 confirma aceptación del comando: observa SSE o `robotState.state` para conocer su ejecución. `401` aplica cuando se configuró token; `403`, a cabecera/origen rechazados; `409`, a modo/estado incompatible; `422`, a cuerpo inválido.

| Endpoint | Entrada | Descripción | Respuestas |
|---|---|---|---|
| `GET /` | Sin cuerpo | Identifica API, frontend y OpenAPI | 200 |
| `GET /api/v1/monitor/snapshot` | Sin cuerpo | Contrato coherente compatible con Angular | 200, 401 |
| `GET /api/v1/monitor/status` | Sin cuerpo | Disponibilidad, modos y estado del robot | 200, 401 |
| `GET /api/v1/monitor/events` | Conexión SSE | `data: JSON` aproximadamente cada 100 ms | 200, 401, 429 |
| `GET /api/v1/monitor/frame` | Sin cuerpo | Último JPEG anotado | 200, 401, 503 |
| `GET /api/v1/monitor/stream` | Conexión MJPEG | Video compartido, `boundary=rony-frame` | 200, 401, 429, 503 |
| `POST /api/v1/robot/commands` | `{"action":"arm"}`, `cancel` o `reset` | Control físico asíncrono | 200, 401, 403, 409, 422 |
| `POST /api/v1/simulation/commands` | `{"action":"pause"}`, `resume` o `cycle` | Control exclusivamente virtual | 200, 401, 403, 409, 422 |
| `POST /api/v1/session` | `{"token":"<24 a 256 caracteres>"}` | Cookie HttpOnly, SameSite Strict, 3600 s | 200, 401, 403, 422 |
| `GET /api/v1/openapi.json` | Sin cuerpo | Esquema generado desde los controladores | 200, 401 |

Las fechas conservan el formato ISO UTC con precisión de milisegundos exigido por Angular. Las conexiones SSE/MJPEG comparten un límite de 8 clientes; el exceso recibe 429 y `Retry-After: 2`. Sin JPEG se responde 503 y `Retry-After: 2`. Tras comenzar un flujo, un fallo puede cerrarlo; no se envía una segunda respuesta HTTP. Los frames se conservan solo como última imagen en memoria.

Configura `RONY_ACCESS_TOKEN` de 24 a 256 caracteres para exigir cookie `rony_session` o `Authorization: Bearer ...`. Sin token, la API local no exige sesión. Escrituras con `Origin` deben coincidir con el host de la solicitud, incluyendo el proxy Angular; no se habilita CORS abierto. La cookie usa Secure bajo HTTPS. La API escucha únicamente en loopback; la configuración JDBC sin TLS está limitada a esta instalación local.

## Cámara y JetMax

Los paths de modelo, notebook y Python se configuran en `application.properties` o sus variables de entorno. El Python de `Entrenamiento/.venv` ya contiene el modelo y sus bibliotecas. `adapters/requirements.txt` fija las dependencias para otro entorno; la versión CUDA de PyTorch depende del equipo. El notebook se lee mediante AST, sin ejecutarlo ni copiar su contraseña. Se admiten `RONY_ROBOT_HOST`, `RONY_ROBOT_USER` y `RONY_ROBOT_PASSWORD`. SSH exige la clave verificada en `~/.ssh/known_hosts` y un bloqueo remoto exclusivo impide dos adaptadores simultáneos.

MIRA conserva `imgsz=640`, `conf=0.20`, `iou=0.45`, salto máximo de 30 píxeles y 50 frames estables. Se muestran plástico, aluminio y cartón; solo plástico con confianza ≥0.80 y proyección válida permite agarre. La calibración K/R/T y el encuadre 4:3 se conservan y se verifican contra ROS. Cada plan debe superar la cinemática remota antes de mover. Zona de prueba: X [-100,100], Y [-240,-90], Z [90,213] mm.

`arm` prepara HOME con pinza vacía, abre a 0°, espera objetivo, cuenta tres segundos y revalida un desplazamiento máximo de 5 mm. Ejecuta aproximación Z=120 (2 s), descenso Z=90 (1 s), cierre 82° (1 s), elevación Z=120 (1.5 s) y HOME (0,-162.94,212.8) (2.5 s) manteniendo la pinza cerrada. Un ciclo termina sin repetir ni depositar. `holdingObject` permanece null: no hay sensor de presencia. `reportedPosition` corresponde al controlador, no a encoders.

`cancel` permite terminar el tramo enviado y bloquea los siguientes; no es parada de emergencia del hardware. `reset` eleva a Z=120 si corresponde, vuelve a HOME y abre a 0°; puede soltar un objeto y no activa otro agarre. Durante un tramo debes cancelar y esperar antes de resetear. Un fallo impide nuevos movimientos y permite reconectar con reset explícito, sin reiniciar la aplicación. Para activar físicamente, confirma pinza vacía, calibración vigente y recorrido despejado.

## Pruebas y empaquetado

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
.\mvnw.cmd test
.\mvnw.cmd verify
```

`verify` ejecuta pruebas, empaqueta el JAR y verifica el formato Java con Spotless y Google Java Format AOSP. Usa `.\mvnw.cmd spotless:apply` para formatear cambios antes del commit. Para las regresiones del adaptador de visión ejecuta, desde la raíz, `..\..\Entrenamiento\.venv\Scripts\python.exe -m unittest discover -s adapters -p "test_*.py"`; no conecta cámara ni ROS.

Las pruebas usan H2 en modo MySQL y robots falsos, con los trabajadores reales desactivados. Verifican límites, secuencia a 82°, cancelación, reset tras fallo, independencia de clasificaciones y agarres, JSON, seguridad, contrato y OpenAPI. No mueven hardware. Para ejecutar el JAR usa `java -jar target/back-rony-1.0.0-SNAPSHOT.jar` desde la raíz del proyecto, con la misma configuración externa y adaptadores disponibles.

| Dependencia | Función |
|---|---|
| Spring Boot Web MVC y Validation | REST, streaming y validación de comandos |
| Spring Data JPA e Hibernate | Persistencia y validación de tablas |
| Flyway y flyway-mysql | Migraciones SQL versionadas |
| MySQL Connector/J | Conexión JDBC local |
| springdoc-openapi 3.1.0 | Esquema OpenAPI y Swagger UI |
| Jackson 3 | DTO y protocolo JSON interno |
| JUnit, Spring Test y H2 | Pruebas aisladas sin actuadores |
| Ultralytics y PyTorch | Inferencia del modelo MIRA en el adaptador nativo |
| OpenCV y NumPy | JPEG, anotación y proyección calibrada |
| Paramiko, rospy y SDK JetMax | SSH verificado y comunicación nativa ROS |

Referencias: [configuración SQL de Spring Boot](https://docs.spring.io/spring-boot/reference/data/sql.html), [migraciones Flyway](https://docs.spring.io/spring-boot/how-to/data-initialization.html), [springdoc](https://springdoc.org/).
