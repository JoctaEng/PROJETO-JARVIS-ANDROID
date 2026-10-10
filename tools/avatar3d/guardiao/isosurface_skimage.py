# Substitui o torchmcubes (precisa compilar) por skimage.measure.marching_cubes, que já vem pronto no Windows.
from typing import Callable, Optional, Tuple

import numpy as np
import torch
import torch.nn as nn
from skimage import measure


class IsosurfaceHelper(nn.Module):
    points_range: Tuple[float, float] = (0, 1)

    @property
    def grid_vertices(self) -> torch.FloatTensor:
        raise NotImplementedError


class MarchingCubeHelper(IsosurfaceHelper):
    def __init__(self, resolution: int) -> None:
        super().__init__()
        self.resolution = resolution
        self._grid_vertices: Optional[torch.FloatTensor] = None

    @property
    def grid_vertices(self) -> torch.FloatTensor:
        if self._grid_vertices is None:
            x, y, z = (
                torch.linspace(*self.points_range, self.resolution),
                torch.linspace(*self.points_range, self.resolution),
                torch.linspace(*self.points_range, self.resolution),
            )
            x, y, z = torch.meshgrid(x, y, z, indexing="ij")
            verts = torch.cat([x.reshape(-1, 1), y.reshape(-1, 1), z.reshape(-1, 1)], dim=-1).reshape(-1, 3)
            self._grid_vertices = verts
        return self._grid_vertices

    def forward(self, level: torch.FloatTensor) -> Tuple[torch.FloatTensor, torch.LongTensor]:
        # o original chama marching_cubes(-level, 0.0); mantém o mesmo sinal e a mesma ordem dos eixos
        vol = (-level.view(self.resolution, self.resolution, self.resolution)).detach().cpu().numpy()
        verts, faces, _, _ = measure.marching_cubes(vol, 0.0)
        verts = torch.from_numpy(verts.copy()).float()
        faces = torch.from_numpy(faces[:, [2, 1, 0]].copy()).long()
        verts = verts / (self.resolution - 1.0)
        return verts.to(level.device), faces.to(level.device)
