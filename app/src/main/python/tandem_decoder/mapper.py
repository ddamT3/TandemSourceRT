from __future__ import annotations

def build_dataset(*args, **kwargs):
	return {
		"meta": {
			"source": {"blobFiles": kwargs.get("blob_files", kwargs.get("blobFiles", []))}
		},
		"timeline": {
			"glucose": [],
			"bolus": [],
			"basal": {"profile": [], "delivered": []},
			"deviceStates": [],
			"events": [],
		},
	}
