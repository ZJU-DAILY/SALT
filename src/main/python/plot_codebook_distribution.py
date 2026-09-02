#!/usr/bin/env python3
"""Create standalone and side-by-side PDF figures for the codebook experiment."""

from __future__ import annotations

import argparse
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from matplotlib.colors import LogNorm


plt.rcParams.update({
    "font.family": "Times New Roman",
    "font.size": 30,
    "axes.titlesize": 32,
    "axes.labelsize": 30,
    "xtick.labelsize": 30,
    "ytick.labelsize": 30,
    "legend.fontsize": 30,
    "figure.titlesize": 32,
    "mathtext.fontset": "stix",
    "pdf.fonttype": 42,
})


DATASET_LABELS = {
    "IR-bio-temp": "IR",
    "Wind-Speed": "WS",
    "Stocks-USA": "SUSA",
    "Stocks-UK": "SUK",
    "PM10-dust": "PM",
    "City-temp": "CT",
    "Stocks-DE": "SDE",
    "SSD-bench": "SSD",
    "waveform_float": "WF",
    "KPI_18fb": "KPI",
    "Dew-point-temp": "DPT",
    "California": "CA",
    "hrl_load_metered": "HRL",
    "electric_vehicle_charging": "EVC",
    "exchange": "ER",
    "Bitcoin-price": "BP",
    "Blockchain-tr": "BL",
    "Basel-wind": "BW",
    "Food-price": "FP",
    "City-lat": "CLA",
    "City-lon": "CLO",
    "Air-pressure": "AP",
    "Basel-temp": "BT",
    "Bird-migration": "BM",
}


def prepare_heatmap(keys: pd.DataFrame, regions: pd.DataFrame):
    """Merge s=0/1 and arrange delta_e on x and delta_u on y."""
    global_99 = regions[
        (regions["scope"] == "global") & (regions["target_coverage"] == 0.99)
    ].iloc[0]
    u_radius = 3
    e_radius = max(1, int(global_99["exponent_radius"]))

    visible = keys[
        (keys["delta_u"].abs() <= u_radius)
        & (keys["delta_e"].abs() <= e_radius)
    ]
    merged = visible.groupby(["delta_u", "delta_e"], as_index=False)["count"].sum()

    matrix = np.zeros((2 * u_radius + 1, 2 * e_radius + 1), dtype=float)
    for row in merged.itertuples(index=False):
        matrix[int(row.delta_u) + u_radius, int(row.delta_e) + e_radius] = row.count

    positive = matrix[matrix > 0]
    if positive.size == 0:
        raise ValueError("No nonzero frequencies are available inside the 99% region")
    return matrix, positive, u_radius, e_radius


def plot_heatmap(axis, keys: pd.DataFrame, regions: pd.DataFrame, title: str):
    """Plot the merged heatmap on an existing axis and return its color mappable."""
    matrix, positive, u_radius, e_radius = prepare_heatmap(keys, regions)
    masked = np.ma.masked_where(matrix == 0, matrix)
    image = axis.imshow(
        masked,
        origin="lower",
        aspect="auto",
        interpolation="nearest",
        extent=(-e_radius - 0.5, e_radius + 0.5, -u_radius - 0.5, u_radius + 0.5),
        cmap="viridis_r",
        norm=LogNorm(vmin=max(1.0, float(positive.min())), vmax=float(positive.max())),
    )
    axis.text(
        -0.02, -0.075, r"$\Delta e$", transform=axis.transAxes,
        ha="right", va="top", clip_on=False,
    )
    axis.set_ylabel(r"$\Delta u$")
    axis.set_xticks(np.arange(-e_radius, e_radius + 1, 1))
    axis.set_yticks(np.arange(-u_radius, u_radius + 1, 1))
    axis.set_title(title)
    axis.axvline(0, color="white", linewidth=0.5, alpha=0.55)
    axis.axhline(0, color="white", linewidth=0.5, alpha=0.55)
    return image


def draw_heatmap(keys: pd.DataFrame, regions: pd.DataFrame,
                 title: str, output: Path) -> None:
    """Draw the merged heatmap as a standalone PDF."""
    figure, axis = plt.subplots(figsize=(9.5, 5.0), constrained_layout=True)
    image = plot_heatmap(axis, keys, regions, title)
    figure.colorbar(image, ax=axis, label="Frequency (log scale)")
    figure.savefig(output)
    plt.close(figure)


def plot_dataset_coverage(axes, datasets: pd.DataFrame) -> None:
    """Plot Top-32 coverage in two columns to preserve labels at low height."""
    ordered = datasets.sort_values("top32_coverage", ascending=False).reset_index(drop=True)
    missing = sorted(set(ordered["dataset"]) - set(DATASET_LABELS))
    if missing:
        raise ValueError(f"Missing display labels for datasets: {missing}")
    axes = np.atleast_1d(axes)
    if len(axes) != 2:
        raise ValueError("Top-32 coverage requires exactly two axes")

    colors = plt.cm.viridis(np.linspace(0.9, 0.15, len(ordered)))
    split = (len(ordered) + 1) // 2
    for column, axis in enumerate(axes):
        start = column * split
        stop = min(start + split, len(ordered))
        subset = ordered.iloc[start:stop]
        positions = np.arange(len(subset))
        axis.barh(
            positions,
            100.0 * subset["top32_coverage"],
            color=colors[start:stop],
            edgecolor="none",
            height=0.72,
        )
        axis.set_yticks(positions, subset["dataset"].map(DATASET_LABELS))
        axis.invert_yaxis()
        axis.axvline(80, color="#d95f02", linestyle="--", linewidth=1.0)
        axis.set_xlim(0, 101)
        axis.tick_params(axis="y", labelsize=30, pad=2)
        axis.grid(axis="x", alpha=0.2, linewidth=0.6)
        axis.set_axisbelow(True)
        axis.margins(y=0.03)

    last_row = len(ordered.iloc[split:]) - 1
    axes[-1].text(82, last_row, "80%", ha="left", va="center")


def draw_dataset_coverage(datasets: pd.DataFrame, output: Path) -> None:
    """Draw the Top-32 coverage panel as a standalone PDF."""
    figure = plt.figure(figsize=(10.5, 5.0), constrained_layout=True)
    axes = figure.subplots(1, 2, sharex=True)
    plot_dataset_coverage(axes, datasets)
    figure.suptitle("(b) Top 32 Structural Transition Coverage")
    figure.savefig(output)
    plt.close(figure)


def draw_combined(keys: pd.DataFrame, regions: pd.DataFrame,
                  datasets: pd.DataFrame, heatmap_title: str, output: Path) -> None:
    """Place the heatmap on the left and dataset coverage on the right."""
    figure = plt.figure(figsize=(20.0, 5.0), constrained_layout=True)
    heatmap_figure, coverage_figure = figure.subfigures(
        1, 2, width_ratios=[9.5, 10.5], wspace=0.03
    )
    heatmap_axis = heatmap_figure.subplots()
    image = plot_heatmap(heatmap_axis, keys, regions, heatmap_title)
    heatmap_figure.colorbar(image, ax=heatmap_axis, label="Frequency (log scale)")
    coverage_axes = coverage_figure.subplots(1, 2, sharex=True)
    plot_dataset_coverage(coverage_axes, datasets)
    coverage_figure.suptitle("(b) Top 32 Structural Transition Coverage")
    figure.savefig(output)
    plt.close(figure)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", nargs="?", default="results/codebook_distribution")
    parser.add_argument("--heatmap-input")
    parser.add_argument(
        "--heatmap-title",
        default="(a) Structural Transition Concentration",
    )
    args = parser.parse_args()

    root = Path(args.input)
    datasets = pd.read_csv(root / "dataset_summary.csv")
    heatmap_root = Path(args.heatmap_input) if args.heatmap_input else root
    keys = pd.read_csv(heatmap_root / "key_distribution.csv")
    regions = pd.read_csv(heatmap_root / "concentration_regions.csv")

    heatmap_output = root / "codebook_heatmap.pdf"
    coverage_output = root / "dataset_top32_coverage.pdf"
    combined_output = root / "codebook_distribution_combined.pdf"
    draw_heatmap(keys, regions, args.heatmap_title, heatmap_output)
    draw_dataset_coverage(datasets, coverage_output)
    try:
        draw_combined(keys, regions, datasets, args.heatmap_title, combined_output)
    except PermissionError:
        combined_output = root / "codebook_distribution_combined_24pt.pdf"
        draw_combined(keys, regions, datasets, args.heatmap_title, combined_output)
    print(
        f"Created: {heatmap_output.resolve()}, {coverage_output.resolve()}, "
        f"{combined_output.resolve()}"
    )


if __name__ == "__main__":
    main()
