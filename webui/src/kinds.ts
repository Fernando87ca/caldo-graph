import type { NodeKind } from "./protocol";

export interface KindInfo {
  label: string;
  stereotype: string;
  icon: string;
}

/** Display data per kind; colors live in styles.css as --kind-<kind> variables. */
export const KINDS: Record<NodeKind, KindInfo> = {
  composablePage: { label: "Composable · Page", stereotype: "Page", icon: "P" },
  composableRouter: { label: "Composable · Router", stereotype: "Router", icon: "R" },
  composable: { label: "Composable", stereotype: "Composable", icon: "C" },
  navigator: { label: "Navigator", stereotype: "Navigator", icon: "N" },
  viewModel: { label: "ViewModel", stereotype: "ViewModel", icon: "VM" },
  useCase: { label: "UseCase", stereotype: "UseCase", icon: "U" },
  repositoryInterface: { label: "Repository (interface)", stereotype: "Repository", icon: "I" },
  repositoryImpl: { label: "Repository (impl)", stereotype: "Repository", icon: "R" },
  dataSource: { label: "API / DataSource", stereotype: "DataSource", icon: "D" },
  model: { label: "Model / State / Event", stereotype: "Model", icon: "M" },
  other: { label: "Other", stereotype: "", icon: "·" },
};

export const KIND_ORDER = Object.keys(KINDS) as NodeKind[];
