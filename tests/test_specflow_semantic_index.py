from pathlib import Path
import sys

import pytest


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


from src.chiselspecflow.semantic_index import (  # noqa: E402
    SemanticIndexError,
    require_confirmed_object,
)


def test_binding_lookup_rejects_unknown_width_or_owner():
    unknown_width = {
        "objects": [
            {
                "name": "counter",
                "fact_status": "ambiguous",
                "owner_module": "first_counter",
                "chisel_type": {"width": None},
                "validation_errors": ["unknown_width"],
            }
        ]
    }
    with pytest.raises(SemanticIndexError, match="not confirmed"):
        require_confirmed_object(unknown_width, "counter")

    unknown_owner = {
        "objects": [
            {
                "name": "counter",
                "fact_status": "elaboration_confirmed",
                "owner_module": "",
                "chisel_type": {"width": 4},
                "validation_errors": [],
            }
        ]
    }
    with pytest.raises(SemanticIndexError, match="lacks width or owner"):
        require_confirmed_object(unknown_owner, "counter")
