"""Vision adapter values. Motion execution lives in the JetMax agent."""
from collections import namedtuple

LABELS = {"plastic": "Plástico", "cardboard": "Cartón", "aluminum": "Aluminio"}

class Observation(namedtuple('ObservationBase', 'jpeg detection stable_frames target_detection visible_count')):
    __slots__ = ()

    def __new__(cls, jpeg, detection, stable_frames=0, target_detection=None, visible_count=0):
        return super(Observation, cls).__new__(cls, jpeg, detection, stable_frames, target_detection, visible_count)
