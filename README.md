# Rony · JetMax autónomo y API web

La referencia vigente es `../rony-yolo-isaac/MovimientoRony/Rony_YOLO11n_Automatico_Windows_Etiquetas_ES.ipynb`. El backend consume `../rony-yolo-isaac/runtime/rony_profile.json`, exportado mediante AST sin ejecutar celdas ni leer credenciales. `runtime/export_notebook.py --check` detecta diferencias. Las pruebas iniciales de agarre a 82° y regreso cerrado fueron sustituidas por el ciclo del notebook actual.

## Arquitectura final preparada

| Ubicación | Componentes |
|---|---|
| JetMax / Jetson Nano | Cámara, YOLO11 TensorRT, filtros/estabilidad, calibración leída de ROS, selección, planificación, ciclo continuo y ejecución ROS. `edge_runtime/run_edge.py` y `rony-edge.service`. |
| Servidor de internet | Angular + Spring sin YOLO/Python/ROS, historial MySQL y proxy HTTPS Caddy. Modo `rony.execution-location=cloud`. |
| PC de desarrollo | Edición, exportación del modelo, instalación y mantenimiento SSH. No participa en la operación final. |
| Otro PC con Isaac | Espejo de telemetría y cámara de Spring, solo lectura. También puede estar en este PC. |

El JetMax inicia una conexión saliente HTTPS a `POST /api/v1/device/exchange`. Envía telemetría, JPEG procesado y resultados, y recibe únicamente `arm`, `cancel` o `reset`. Spring no selecciona residuos ni calcula movimientos en modo cloud. La web consulta a Spring mediante SSE/MJPEG autenticados; no se conecta directamente a ROS o la cámara. SSH queda para instalación/mantenimiento. No hace falta publicar puertos del brazo ni tener encendido el PC.

Hardware comprobado: Jetson Nano 4 GB, Ubuntu 18.04.6, Python 3.6.9, L4T 32.6.1, CUDA 10.2 y TensorRT 8.0.1.6. Se conserva esa instalación. El modelo actual se exportó a ONNX opset 12 en el PC, se compiló en el JetMax y se ejecutó con TensorRT/PyCUDA sin PyTorch moderno. La prueba de cámara/inferencia y la integración con filtros/calibración/destinos pasaron sin movimientos; informes locales en `data/jetson-model-benchmark.json` y `data/jetson-runtime-check.txt`. Los servicios de operación permanente y el despliegue público requieren activación posterior; las pruebas de lectura no los activan.

### Comunicación y recuperación

La clave del dispositivo es distinta de la clave web. El endpoint de dispositivo rechaza sesiones del navegador y cabeceras Origin. Las órdenes quedan ligadas al identificador de arranque del JetMax, caducan en diez segundos y se registran en SQLite para no ejecutarlas dos veces. Tras un reinicio no se reanuda un ciclo pendiente. Los resultados se guardan localmente y se reenvían hasta recibir confirmación; su UUID evita duplicar el historial del servidor.

El ciclo y los ocho frames de estabilidad se deciden dentro del brazo. Si la API no responde durante quince segundos, el controlador local cancela los siguientes tramos; termina el tramo ya emitido. Requiere revisión/reset y activación explícita cuando vuelve la conexión. La API marca el dispositivo sin conexión tras seis segundos sin recibir datos. Ninguna de estas acciones es una parada eléctrica de emergencia.

### Preparar y comprobar la parte del JetMax

```powershell
./inspect-robot.ps1       # Ya ejecutado: inventario real, solo lectura
./prepare-edge-model.ps1  # Ya ejecutado: compilación TensorRT y prueba de cámara, cero movimientos
./check-edge-runtime.ps1  # Ya ejecutado: visión/calibración/ROS/destinos integrados, cero movimientos
```

El motor permanece en `/home/hiwonder/rony-model-check` y se reutiliza si coinciden sus hashes y la versión TensorRT. El contexto CUDA se mantiene activo mientras se liberan las asignaciones de TensorRT. Los chequeos no sustituyen servicios del fabricante, no escriben `/camera_cal/card_params` y no publican movimientos.

### Publicar cuando exista servidor y dominio

Todavía no se eligieron servidor ni dominio. No se contrató ni publicó ningún recurso. El archivo `deploy/compose.yaml` prepara Caddy, la web/API Java y MySQL persistente; solo expone 80/443. La API y MySQL se comunican en la red privada de Docker. La imagen de la API no contiene Python ni el modelo YOLO. La configuración Compose se valida localmente; su ejecución en contenedores requiere un motor Docker activo.

1. Disponer de servidor Linux con Docker/Compose y dominio apuntando a su IP. Sincronizar la hora del JetMax y conectarlo a una red con internet: su reloj actual está desajustado, como muestran los avisos de tar; HTTPS valida las fechas de los certificados.
2. Ejecutar `./prepare-cloud.ps1 -Domain DOMINIO_REAL` en el PC. Genera credenciales privadas en `deploy/.env` y completa `cloud_api_url`/`device_token` en `config.local.json`. No publica ni contrata nada.
3. Copiar los proyectos y la configuración privada al servidor; desde `back-temp-rony/deploy`, ejecutar `docker compose up -d --build`. Caddy obtiene el certificado del dominio real. No se desactiva la validación TLS.
4. Con el JetMax y el banco detenidos, ejecutar `./activate-edge.ps1` en el PC. Comprueba la API remota, vuelve a verificar el runtime local y sustituye únicamente nuestro `rony-agent` por `rony-edge`. El inicio solo observa. Conserva ROS y la calibración. Si falla el arranque intenta restaurar el ejecutor anterior.
5. Abrir la web HTTPS y activar desde Monitoreo. Verificar recogida y descarga reales antes de dejar el proceso desatendido.

Comandos de mantenimiento del nuevo servicio: `systemctl status rony-edge` y `journalctl -u rony-edge -n 80 --no-pager`. Para volver al banco, detener `rony-edge` y habilitar/iniciar `rony-agent`; nunca operar ambos controladores simultáneamente. `run.ps1 -Mode Physical` conserva el **banco anterior**, con YOLO en el PC; no es el arranque de la arquitectura autónoma final.

## Netlify, Render y Aiven

Las direcciones públicas son `https://rony-brazo.netlify.app` y `https://rony-api.onrender.com`. La configuración local del JetMax ya puede prepararse con `./prepare-render.ps1 -ApiUrl https://rony-api.onrender.com`. Netlify conserva `/api` en el origen del navegador mediante su proxy HTTPS.

El perfil Render mantiene el estado mediante `polling` y conserva `jpeg` como alternativa. El vídeo se obtiene directamente desde Render: la sesión HttpOnly de Netlify solicita `GET /api/v1/monitor/camera-access`, que entrega una capacidad de cámara de un solo uso, válida 30 segundos, y una URL HTTPS sin credenciales. El navegador presenta esa capacidad en la cabecera `Authorization: Camera ...` de `camera-stream`. CORS permite solo el origen configurado del front. Ese permiso no sirve para controlar el brazo. Cada stream dura como máximo dos minutos; después se solicita otro permiso. No se publican puertos del JetMax.

El visor procesa MJPEG incrementalmente, conserva la imagen más reciente, descarta las acumuladas y cierra el vídeo cuando la pestaña queda oculta. El JetMax reutiliza una conexión HTTPS verificada y sigue el intervalo de 200 ms de la API, contando el tiempo de envío dentro de ese plazo. El máximo previsto es 5 imágenes/s, sujeto a inferencia, ancho de banda y latencia; no modifica resolución/calibración, filtros ni trayectorias. Para actualizar el cliente del brazo, detener el automático y volver a ejecutar `./activate-edge.ps1` después de desplegar el backend. La pérdida de conexión conserva el lease de control de 15 s y no reanuda movimientos automáticamente.

Los totales del historial se calculan en una consulta agregada y se guardan en caché cinco segundos; un resultado del brazo invalida la caché inmediatamente. Una cabecera Accept incompatible devuelve 406 vacío, sin intentar serializar otra respuesta JSON incompatible.

Con la clasificación detenida y el JetMax conectado a una WiFi con internet, `./activate-edge.ps1` instala nuestro servicio autónomo. SSH/SCP/sudo pedirán la contraseña Linux. Antes de detener el ejecutor anterior, sincroniza la hora UTC con la del PC, solicita NTP y comprueba DNS/HTTPS y el estado de la API. Luego verifica YOLO/ROS/calibración en lectura, instala `rony-edge` y espera que Render confirme telemetría del brazo. Guarda `data/jetson-online-status.json`. El arranque solo observa y no inicia movimientos. Si el cambio de WiFi da otra IP al JetMax, usar `-RobotHost IP_ACTUAL`. Estas comprobaciones no configuran redes WiFi ni modifican ROS o la calibración.

El perfil `render` permite 45 s para obtener/iniciar sesión en una conexión de la base y un margen inicial de 60 s. El valor de 5 s del banco local también limita el login JDBC y puede caducar durante el primer handshake TLS en una instancia con poca CPU. TCP y lecturas JDBC tienen límites de 30 s. Los plazos siguen acotados y se mantiene la verificación de certificado y hostname; no es necesario cambiar credenciales o desactivar SSL por este error.

El front publicado es `https://rony-brazo.netlify.app`. El `Dockerfile` de la raíz del backend construye únicamente este repositorio con Java 21; no necesita Angular, Python, YOLO ni los otros repositorios dentro de Render. `deploy/Dockerfile` conserva la alternativa de servidor unificado anterior y no se debe seleccionar para este despliegue.

1. Guardar las credenciales de Aiven en `deploy/.env.aiven` y su CA en `deploy/aiven-ca.pem`, ambos ignorados por Git. Ejecutar `./prepare-render.ps1`. Conserva la clave web, genera una clave distinta para el JetMax si falta y prepara `deploy/.env.render` para importar en Render. No publica servicios ni modifica el brazo.
2. En Render crear **Web Service**, conectar `back-temp-rony`, rama `main`, lenguaje **Docker**, raíz vacía, Dockerfile `./Dockerfile`, contexto `.` y Docker Command vacío.
3. Importar las variables del archivo privado `deploy/.env.render`. Añadir un **Secret File** llamado exactamente `aiven-ca.pem` con el contenido del certificado. El inicio transforma esa CA en un almacén temporal; los secretos no se copian a la imagen.
4. Usar `/health` como Health Check Path. Devuelve 200 si la base responde, incluso cuando el brazo todavía no está conectado; la API de cámara y control sigue autenticada. Render proporciona `PORT` y `RENDER_EXTERNAL_HOSTNAME` automáticamente.
5. Desplegar. El perfil `render` usa `0.0.0.0`, el puerto de Render, Aiven con `sslMode=VERIFY_IDENTITY` y Flyway para crear/actualizar las tablas. No desactiva SSL ni habilita limpieza automática de datos. No ejecuta YOLO o ROS.
6. Con la URL HTTPS real del backend, ejecutar `./prepare-render.ps1 -ApiUrl https://URL_REAL.onrender.com`. Esa dirección se guarda en la configuración privada para el JetMax. Después configurar en Netlify el proxy `/api/*` hacia ese backend antes de la regla SPA, comprobar sesión/telemetría/cámara y activar el servicio autónomo del brazo.

La sesión conserva su cookie HttpOnly, Secure en HTTPS y SameSite=Strict: el navegador debe usar `/api` a través del proxy de Netlify. El backend acepta ese origen público exacto y mantiene la autenticación y la cabecera de control; no abre CORS a cualquier sitio. Falta comprobar la conexión entre ambos servicios una vez que exista la URL real de Render. Un servicio Render Free puede dormir por inactividad; las pruebas de recuperación deben incluir ese caso.

`deploy/rony_profile.json` es la copia de distribución del perfil exportado del notebook y permite construir el backend de forma independiente. `prepare-render.ps1` la sincroniza y una prueba verifica que coincide con el perfil canónico. No contiene la calibración ROS activa ni credenciales; no escribe parámetros del brazo. Para actualizar el notebook/perfil, volver a sincronizar y confirmar la copia antes de desplegar.

## Banco local anterior: preparación y arranque en este PC

Desde esta carpeta:

```powershell
./prepare.ps1
./run.ps1 -Mode Simulation
```

La preparación crea `../rony-yolo-isaac/.venv`, usa un Python 3.11 bajo `../.runtime`, instala las versiones del notebook, genera dos claves privadas distintas y empaqueta web/API y agente. En este PC ya están preparados. `config.local.json` conserva la configuración existente y queda ignorado por Git. La clave de la web es `web_access_token`; la del brazo es `robot_agent_token`. No se imprimen ni se almacenan en localStorage/sessionStorage.

Abre `http://127.0.0.1:8000/monitoring` e introduce la clave web de `config.local.json`. La interfaz y `/api` comparten origen; no necesitas `ng serve` para probar el paquete.

```powershell
./run.ps1 -Mode Live       # Cámara y detección reales; movimientos virtuales
./run.ps1 -Mode Physical   # Cámara real y agente ROS; arranque sin movimientos
./run.ps1 -Mode Physical -RobotHost 192.168.1.50
./run.ps1 -Mode Physical -Database MySQL
./run.ps1 -Mode Simulation -Rebuild
```

Las pruebas físicas usan por defecto H2 persistente en `data/rony_bench`, sin depender de credenciales MySQL ausentes. `-Database MySQL` utiliza `RONY_DB_*` o `application-local.properties`. El modo Simulation utiliza H2 en memoria y distingue sus datos de los físicos. No se sustituye un hardware desconectado por un robot falso.

## Ejecutor del banco anterior: instalación dentro del brazo

El paquete preparado es `robot_agent/dist/rony-agent.tar.gz`. Contiene una clave privada: no lo publiques. Cópialo al JetMax mediante NoMachine, USB o transferencia SSH inicial. En una terminal del robot:

```bash
mkdir -p ~/rony-install
 tar -xzf ~/rony-agent.tar.gz -C ~/rony-install
cd ~/rony-install
sudo bash install.sh
systemctl status rony-agent
journalctl -u rony-agent -n 80 --no-pager
```

La instalación añade `/opt/rony-agent`, `/etc/rony-agent/agent.env` y `rony-agent.service`; no reemplaza la imagen ni los servicios del fabricante. El servicio arranca con el equipo y solo observa hasta recibir una trayectoria. Reintenta si ROS todavía no está disponible. El entorno `bash -ic` conserva la carga de ROS usada por el notebook; si tu instalación la define en otro archivo, configura `RONY_ROS_SETUP` y `RONY_ROS_PYTHON` en `/etc/rony-agent/agent.env`.

ROS debe publicar `/jetmax/status` y atender `/jetmax/command` y `/jetmax/end_effector/servo1/command`; la cámara sigue en el puerto 8080. El agente escucha en 8090 dentro de la LAN/VPN. Exige un token y rechaza un perfil diferente al del PC. Para actualizar únicamente nuestro agente desde PowerShell, ejecuta `./update-agent.ps1`; solicitará la contraseña Linux para SCP/SSH/sudo. No modifica archivos ni parámetros de calibración. No ejecutes a la vez los notebooks de movimiento y el servicio controlador.

### La calibración pertenece al brazo

La referencia para la cámara real es **`/camera_cal/card_params` dentro del JetMax**. Se conserva exactamente como está. El agente solo lee K/R/T y los publica en `GET /v1/calibration`; no tiene una operación para escribirlos. Spring entrega esa lectura al trabajador YOLO por su canal privado, sin entregarle credenciales del agente. La proyección usa esos valores en memoria: no sustituye ni reescribe la calibración del brazo o el notebook.

El K/R/T incluido en el perfil exportado conserva la referencia del notebook para consultas y pruebas offline; no es la calibración activa de la cámara real. Si no se puede leer una calibración válida del brazo, el video puede continuar, pero no se generan coordenadas para recoger. Cada observación y trayectoria identifica la calibración utilizada. Una diferencia bloquea los siguientes movimientos; nunca se corrige escribiendo parámetros ROS.

Esta integración requiere el protocolo 2 de nuestro agente. El backend rechaza la versión anterior con un mensaje de actualización, en lugar de aceptar una trayectoria sin identificar su calibración. El arranque y la actualización del servicio siguen sin mover el brazo.

## Wi-Fi del brazo e internet

El JetMax puede dejar su hotspot AP y conectarse al router en modo LAN/STA. El fabricante documenta el cambio desde la aplicación y cómo consultar la nueva IP; consulta [JetMax: conexión a la red local](https://wiki.hiwonder.com/projects/JetMax/en/latest/docs/6.Advanced%20Program%20Lessons.html#_6-1-7-lesson-7-jetson-nano-network-connection). La IP `192.168.149.1` corresponde al acceso directo del montaje actual; no se conserva necesariamente al cambiar al router.

Conecta el JetMax al router y reserva una IP mediante DHCP para facilitar mantenimiento. Actualiza `rony_host` en `config.local.json` si cambia. En el banco anterior PC y brazo necesitan la misma red; en la arquitectura final el JetMax solo necesita alcanzar la API pública por HTTPS. El PC no participa en la operación cotidiana. SSH queda para instalación/mantenimiento, no para cada recogida.

Tener Wi-Fi con internet no publica la API del brazo. El despliegue final usa un dominio HTTPS para web/API y conexiones salientes iniciadas por `rony-edge`; no usa túnel SSH ni depende del PC. ROS, 8090, la cámara y MySQL permanecen fuera de puertos públicos. Caddy termina TLS y entrega las cabeceras del proxy a Spring dentro de la red privada del servidor.

## Ciclo vigente y destinos provisionales

Se conservan del notebook: `imgsz=640`, confianza de visualización 0.60, IoU 0.30, NMS por objeto, máximo 20 detecciones, filtros de tamaño/borde, confianza automática 0.80, **8 frames** y salto máximo 30 px. La calibración K/R/T de la cámara real se lee del brazo. Se habilita en la aplicación el enrutamiento de plástico, aluminio y cartón a tres destinos. El notebook original conserva su selección de plástico.

Secuencia: HOME y apertura → detección reciente → colocarse sobre el objeto a Z=212.8 (2.5 s) → bajar en el mismo X/Y a Z=120 (2 s) → bajar a Z=90 (2 s) → cierre 80° (2 s) → estabilización del cierre (1 s) → elevar en el mismo X/Y a Z=120 (2 s) → elevar en el mismo X/Y a Z=212.8 (3 s) → volver a HOME manteniendo Z=212.8 (3 s) → girar por puntos intermedios hasta el destino sin bajar → apertura (1 s) → regreso por los puntos en orden inverso → HOME → nueva detección. Cada tramo de giro dura 2.5 s y conserva radio/altura de HOME, con incrementos máximos de 30°. Se espera también coincidencia estable de consigna durante 0.4 s y estabilización de 0.7 s tras los demás tramos. No hay cuenta regresiva de tres segundos.

El agente exige la política `vertical-home-before-disposal-v1`: valida la trayectoria completa antes de aceptarla y vuelve a comprobar la altura antes de publicar cada movimiento. Rechaza cualquier cambio lateral que empiece o termine por debajo de la altura HOME (con la tolerancia del perfil) o que mezcle traslado lateral con elevación/descenso. Después del agarre exige pasar por HOME antes del giro de descarga. Reset también eleva en el mismo X/Y hasta la altura HOME antes de regresar. Spring y el chequeo rechazan un agente anterior que no declare esta protección. La cinemática debe validar también el punto alto sobre el residuo; si no es alcanzable se rechaza la trayectoria completa antes de recoger.

| Material | Base absoluta | X mm | Y mm | Z mm |
|---|---:|---:|---:|---:|
| Aluminio | 190° | 153.1135 | -55.7288 | 212.8 |
| Cartón | 210° | 162.94 | 0 | 212.8 |
| Plástico | 230° | 153.1135 | 55.7288 | 212.8 |

En la [cinemática oficial de JetMax](https://github.com/JetMaxRoboticArm/jetmax_ebap/blob/main/src/kinematic.py), HOME corresponde a 120° y el recorrido nominal de la base es 0°–240°. El programa restringe sus órdenes a 10°–230°; no cambia límites ni calibración del controlador. El agente calcula los ángulos/pulsos con el SDK instalado antes de cada trayectoria. La zona de recogida conserva X=−100..100, Y=−240..−90 y Z=90..213 mm; fuera de ella solo se autorizan los puntos exactos de transporte del perfil. Los destinos están fuera de la recogida y mantienen una exclusión de 40 mm. Todavía falta comprobar espacio libre y depósito real; no hay tachos medidos.

El automático permanece activo hasta cancelar o hasta un fallo de cámara, ROS, calibración o trayectoria. Tras cada regreso a HOME descarta el objetivo anterior y requiere ocho observaciones nuevas. Mesa vacía y `SEARCH_TIMEOUT` cambian el mensaje de espera, sin finalizar la clasificación. Si el mismo residuo persiste tras tres intentos, visión lo omite y busca otro; vuelve a admitirlo tras doce observaciones de ausencia mientras observa desde HOME, o si se desplaza fuera de su zona de repetición. Los movimientos de cámara durante el transporte no cuentan como retirada del residuo. Cancelar permite terminar el tramo ya publicado y bloquea los siguientes. Una conexión de control perdida durante 5 s impide el siguiente tramo. Un fallo o reinicio del agente requiere reset explícito; no se reanuda un movimiento pendiente.

## Controles manuales del panel del ojo

Al final del panel **Control del brazo**, en Monitoreo, aparecen dos botones disponibles con el automático detenido y sin otro movimiento en curso:

- **Cerrar pinza · 90°** publica `jetmax_control/SetServo` en `/jetmax/end_effector/servo1/command` con `data: 90, duration: 1.0`. El cierre de clasificación conserva sus 80°.
- **Posición de guardado** publica `jetmax_control/SetJetMax` en `/jetmax/command` con `x: -42.6878, y: -27.0905, z: 225.0, duration: 3.0`. Requiere partir de HOME. Este punto fijo es la única excepción manual al límite de altura de clasificación; se comprueba con la cinemática y los límites del controlador original. No modifica la calibración.

Después de cerrar manualmente o guardar, **Resetear** devuelve el brazo a HOME y abre la pinza antes de habilitar el automático. Estos controles no añaden recogidas ni clasificaciones al historial. La web solo envía las acciones fijas `close-gripper` y `park`; el agente publica los mensajes ROS, sin abrir SSH ni recibir comandos de shell desde el navegador. Para el banco local, actualizar `rony-agent` mediante `./update-agent.ps1` antes de usar estos botones: requieren `manualControlsVersion: 1`. El runtime autónomo `rony-edge` incorpora los mismos controles.

## Historial y límites de verificación

`robot_grasp_cycle` registra recorridos que incluyeron el cierre actual a 80°. Los registros históricos a 82° se conservan. Las tres clases están admitidas por la migración V2, sin reescribir V1. Las trayectorias completas se guardan en `classification_operation` con destino y resultado `unverified`: **pendiente de comprobar depósito**, no éxito inventado. Los fallos de trayectoria se registran como `failure`. No hay sensor de presencia ni encoders; `holdingObject` y `depositConfirmed` permanecen null. El visor y la cámara permiten revisar, pero no certifican un agarre automáticamente.

## Comprobar antes de mover

```powershell
./check-ready.ps1                         # Perfil, Python y una inferencia real sin cámara
./check-ready.ps1 -Robot -RobotHost 192.168.1.50  # Lee agente/ROS/calibración/cámara y valida destinos; cero movimientos
```

El chequeo físico también ejecuta `/v1/preflight`: valida controladores ROS, calibración leída del brazo, HOME y tres destinos, sin publicar comandos de servos. Un chequeo de conexión por sí solo no acredita esta preparación. Cuando ambas comprobaciones pasen, abre Monitoreo y activa el ciclo con el brazo en HOME, pinza vacía y recorrido revisado. Cancelar no es una parada de emergencia eléctrica. Reset vuelve a HOME y abre la pinza.

## Pruebas y empaquetado

```powershell
./mvnw.cmd verify
../rony-yolo-isaac/.venv/Scripts/python.exe -m unittest discover -s adapters -p "test_*.py"
../rony-yolo-isaac/.venv/Scripts/python.exe -m unittest discover -s robot_agent -p "test_*.py"
./package.ps1
./robot_agent/package.ps1
```

Las pruebas Java incluyen comunicación HTTP con el agente Python en `--fake`, sin ROS ni hardware. El modo físico rechaza un agente falso por defecto. `rony.allow-test-agent=true` está reservado a pruebas expresas. Las pruebas cubren secuencia completa, tres destinos, cancelación, pérdida de lease, idempotencia, formato de telemetría, persistencia y resultados sin confirmar. El README es la documentación Markdown del backend.

API: `GET /api/v1/monitor/snapshot`, `status`, `events`, `frame`, `stream`; `POST /api/v1/robot/commands`, `simulation/commands`, `session`; OpenAPI en `/api/v1/openapi.json`. Los POST exigen JSON, `X-Rony-Control: 1`, origen coincidente y sesión cuando se configura token. SSE y MJPEG comparten 8 conexiones; una vista con video usa dos. Sin imagen se devuelve 503 y, al saturar streams, 429.
