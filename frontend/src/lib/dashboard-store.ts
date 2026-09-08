import { create } from "zustand";
import { MOCK_LINKS, type MockLink } from "@/lib/mock-data";

interface DashboardState {
  links: MockLink[];
  search: string;
  setSearch: (search: string) => void;
  addLink: (link: MockLink) => void;
}

export const useDashboardStore = create<DashboardState>((set) => ({
  links: MOCK_LINKS,
  search: "",
  setSearch: (search) => set({ search }),
  addLink: (link) => set((state) => ({ links: [link, ...state.links] })),
}));
