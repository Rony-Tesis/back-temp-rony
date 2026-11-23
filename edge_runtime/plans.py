"""Robot-local planning: vertical clearance, HOME, disposal, HOME. Python 3.6."""
import math


def step(stage, label, duration, position=None, gripper=None):
    result = {'stage': stage, 'label': label, 'duration': duration}
    result.update(position=list(position)) if position is not None else result.update(gripper=gripper)
    return result


def home(profile):
    return [profile.values['HOME_' + axis] for axis in ('X', 'Y', 'Z')]


def near(pose, point, tolerance=2):
    return all(abs(pose[key] - value) <= tolerance for key, value in zip(('x', 'y', 'z'), point))


def prepare(profile, pose):
    if not near(pose, home(profile)):
        raise ValueError('Prepara HOME antes de activar la clasificación')
    return [step(0, 'Preparando HOME', profile.values['HOME_TIME'], home(profile)),
            step(0, 'Abriendo pinza vacía', profile.values['GRIPPER_OPEN_TIME'], gripper=0)]


def sort(profile, target):
    values, data = profile.values, profile.data
    if (not isinstance(target, dict) or target.get('category') not in data['enabledCategories']
            or target.get('confidence', 0) < values['AUTO_MIN_CONF'] or not target.get('targetMm')):
        raise ValueError('Se requiere un residuo estable y apto')
    x, y = target['targetMm']['x'], target['targetMm']['y']
    workspace = data['workspaceMm']
    if not (math.isfinite(x) and math.isfinite(y) and workspace['x'][0] <= x <= workspace['x'][1]
            and workspace['y'][0] <= y <= workspace['y'][1]):
        raise ValueError('Residuo fuera del área de recogida')
    if any(math.hypot(x - b['positionMm'][0], y - b['positionMm'][1]) < values['DROP_EXCLUSION_MM']
           for b in data['bins'].values()):
        raise ValueError('Residuo dentro del área de descarga')
    destination = data['bins'][target['category']]
    result = [
        step(2, 'Sobre el residuo a altura HOME', values['APPROACH_TIME'], [x, y, values['HOME_Z']]),
        step(2, 'Bajando verticalmente a aproximación', values['VERTICAL_TIME'], [x, y, values['Z_APPROACH']]),
        step(3, 'Bajando verticalmente para recoger', values['VERTICAL_TIME'], [x, y, values['Z_PICK']]),
        step(3, 'Cerrando pinza a 80°', values['GRIPPER_CLOSE_TIME'], gripper=values['GRIPPER_PICK']),
        step(4, 'Levantando verticalmente', values['VERTICAL_TIME'], [x, y, values['Z_APPROACH']]),
        step(4, 'Elevando verticalmente a altura HOME', values['HOME_TIME'], [x, y, values['HOME_Z']]),
        step(4, 'HOME antes de girar para descargar', values['HOME_TIME'], home(profile))]
    result += [step(4, 'Girando en alto hacia ' + destination['label'], values['SIDE_TIME'], point)
               for point in destination['transitMm']]
    result += [step(5, 'Abriendo pinza en ' + destination['label'], values['GRIPPER_OPEN_TIME'], gripper=0)]
    result += [step(5, 'Regresando en alto a HOME', values['SIDE_TIME'], point)
               for point in list(reversed(destination['transitMm'][:-1])) + [home(profile)]]
    return profile.steps(result)


def reset(profile, pose):
    values = profile.values
    result = []
    if pose['z'] < values['HOME_Z'] - values['POSITION_TOLERANCE']:
        result.append(step(0, 'Elevando verticalmente a altura HOME', values['HOME_TIME'],
                           [pose['x'], pose['y'], values['HOME_Z']]))
    back = [home(profile)]
    for destination in profile.data['bins'].values():
        for index, point in enumerate(destination['transitMm']):
            if near(pose, point):
                back = list(reversed(destination['transitMm'][:index])) + [home(profile)]
                break
    result += [step(0, 'Regresando en alto a HOME', values['HOME_TIME'], point) for point in back]
    result.append(step(0, 'Abriendo pinza en HOME', values['GRIPPER_OPEN_TIME'], gripper=0))
    return profile.steps(result)
