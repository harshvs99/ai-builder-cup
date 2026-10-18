"""Write one JSON Schema containing every exported contract model (consumed by gen_types.sh)."""

import json
import sys
from pathlib import Path

from pydantic.json_schema import models_json_schema

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from app.tools.schemas import EXPORTED_MODELS  # noqa: E402

_, schema = models_json_schema(
    [(m, "serialization") for m in EXPORTED_MODELS], ref_template="#/$defs/{model}"
)
schema["title"] = "HoldOnContracts"
# Make every model a top-level named type in the generated TS.
schema["type"] = "object"
schema["properties"] = {m.__name__: {"$ref": f"#/$defs/{m.__name__}"} for m in EXPORTED_MODELS}
out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("contracts.schema.json")
out.write_text(json.dumps(schema, indent=2))
print(f"wrote {out}")
